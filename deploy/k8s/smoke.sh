#!/usr/bin/env bash
# Deploys the service to a local kind cluster and checks it keeps working while its pods are replaced
# (docs/episodes/bonus-13). Needs docker, kind, kubectl, curl and jq. From the repository root:
#   deploy/k8s/smoke.sh            create the cluster if needed, build, deploy, test
#   kind delete cluster --name payments   when done
#
# While payments are created and executed in a loop, the pods are replaced twice: a rolling restart, then a drain of
# the node one of them runs on (not on a single-node cluster). It fails if any create or execute gets anything but its
# 201 or 202 (a 5xx, or no answer at all), or if any payment doesn't reach a final status.
set -euo pipefail

for tool in docker kind kubectl curl jq; do
  command -v "$tool" >/dev/null || { echo "Missing: $tool"; exit 1; }
done
# The Dockerfile's cache mount needs BuildKit, which docker build only has through the buildx plugin. Docker Desktop
# ships it; on Ubuntu's docker.io package: sudo apt install docker-buildx.
docker buildx version >/dev/null 2>&1 || { echo "Missing: docker buildx (Ubuntu: sudo apt install docker-buildx)"; exit 1; }

cluster=payments
namespace=payments
url=http://localhost:30080
# CUST-1001, signed with the local key in the overlay's Secret (http/payments.http). Local use only.
token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJDVVNULTEwMDEifQ.dBtBctssK74kMTXqNo4zPSkk--7cSdouBA4q40hCBb8
k() { kubectl --context "kind-$cluster" -n "$namespace" "$@"; }

if ! kind get clusters | grep -qx "$cluster"; then
  kind create cluster --name "$cluster" --config deploy/k8s/kind.yaml
fi
docker build -t payment-service:local .
kind load docker-image payment-service:local --name "$cluster"
kubectl kustomize --load-restrictor LoadRestrictionsNone deploy/k8s/local | kubectl --context "kind-$cluster" apply -f -
# The same tag every time: a rebuilt image only reaches the pods through a restart.
k rollout restart deployment/payment-service
for deployment in postgres kafka schema-registry external-systems payment-service; do
  k rollout status "deployment/$deployment" --timeout=5m
done

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT

disrupt() {
  sleep 5
  echo "--- rolling restart"
  k rollout restart deployment/payment-service
  k rollout status deployment/payment-service --timeout=5m
  # On one node the drain would wait forever: the PodDisruptionBudget keeps the last pod, with nowhere to move it.
  if (( $(kubectl --context "kind-$cluster" get nodes -o name | wc -l) == 1 )); then
    echo "--- one node: no drain"; return
  fi
  node=$(k get pods -l app.kubernetes.io/name=payment-service -o jsonpath='{.items[0].spec.nodeName}')
  echo "--- draining $node"
  # Only this service's pods are evicted, as the PodDisruptionBudget allows: the local stand-ins have no replicas.
  kubectl --context "kind-$cluster" drain "$node" --pod-selector app.kubernetes.io/name=payment-service \
    --ignore-daemonsets --timeout=5m
  k rollout status deployment/payment-service --timeout=5m
  kubectl --context "kind-$cluster" uncordon "$node"
  sleep 5
}

# Prints the status code, or 000 when there was no answer.
call() {
  curl -s -o "$workdir/body" -w '%{http_code}' --max-time 15 -H "Authorization: Bearer $token" "$@" || true
}

disrupt &
disruptor=$!
requests=0
failures=0
: > "$workdir/payments"
while kill -0 "$disruptor" 2>/dev/null; do
  status=$(call -X POST "$url/payments" -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" \
    -H 'Content-Type: application/json' \
    -d '{"sourceAccountId":"ACC-10001","destinationAccountId":"ACC-20001","amount":10.00,"currency":"EUR","reference":"Smoke"}')
  requests=$((requests + 1))
  if [[ $status != 201 ]]; then
    echo "create: $status $(cat "$workdir/body")"; failures=$((failures + 1)); continue
  fi
  id=$(jq -r .paymentId "$workdir/body")
  echo "$id" >> "$workdir/payments"
  status=$(call -X POST "$url/payments/$id/execute")
  requests=$((requests + 1))
  if [[ $status != 202 ]]; then
    echo "execute $id: $status $(cat "$workdir/body")"; failures=$((failures + 1))
  fi
done
wait "$disruptor"

echo "--- $requests requests, $failures failed; waiting for $(wc -l < "$workdir/payments") payments to be final"
deadline=$((SECONDS + 120))
while read -r id; do
  until [[ $(call "$url/payments/$id") == 200 ]] && jq -e '.status | IN("COMPLETED","FAILED","CANCELLED")' "$workdir/body" >/dev/null; do
    if ((SECONDS > deadline)); then
      echo "not final: $id $(cat "$workdir/body")"; failures=$((failures + 1)); break
    fi
    sleep 1
  done
done < "$workdir/payments"

if ((failures > 0)); then
  echo "FAILED: $failures"; exit 1
fi
echo "OK: no failed request, every payment final"

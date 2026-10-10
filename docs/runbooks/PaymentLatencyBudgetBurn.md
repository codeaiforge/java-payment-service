# PaymentLatencyBudgetBurn

**What it means.** Too many `POST /payments` took longer than 500ms. The SLO allows 1%. `window="fast"` pages: the
month's budget is gone in about 2 days at this rate. `window="slow"` is a ticket: about 5 days.

Creating a payment asks the account system about both accounts (Episode 07) and writes to PostgreSQL. One of those
is usually the slow part.

## First three checks

1. **A slow request, as a trace.** Grafana, Prometheus data source, query
   `http_server_requests_seconds_bucket{uri="/payments",method="POST"}`, with **Exemplars** on in the query
   options. Each dot is a real request; click one above 0.5s and "Query with Tempo". The trace shows which span took
   the time.
2. **The account system.** `histogram_quantile` isn't available (only the 500ms boundary is published), so look at
   the average. A timeout is retried (Episode 16), so one hanging account system costs up to three read timeouts.
   ```promql
   rate(http_client_requests_seconds_sum{clientName=~".*AccountClient"}[5m])
     / rate(http_client_requests_seconds_count{clientName=~".*AccountClient"}[5m])
   ```
3. **The database.** `hikaricp_connections_pending` above 0 means requests wait for a connection.

SLI: `payment:create_slow:ratio_rate1h` (and `5m`, `30m`, `6h`).

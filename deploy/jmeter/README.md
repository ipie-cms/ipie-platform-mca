# Load tests

Two plans, measuring different things.

| Plan | What it exercises |
|---|---|
| `ipie-service-template-load-test.jmx` | A service serving its own data: token, then `GET /api/v1/users`. |
| `ipie-registration-load-test.jmx` | The **cross-service** path: registration through to `complete`, which calls ipie-iam-service via `InterServiceClient`. |

The second one exists because nothing else exercises inter-service resilience. `InterServiceClient`
wraps every call in Bulkhead → RateLimiter → CircuitBreaker → Retry (see
`ipie-resilience-defaults.yml`), and a plan that only reads a service's own data never touches any
of it — the resilience4j registries create their instances lazily, so a service that has made no
outbound call reports no resilience4j metrics at all.

## Running

Both run from a container on the platform network, so they can reach services by name:

```bash
cd deploy/jmeter
docker run --rm --network ipie-platform-mca_default --add-host keycloak:host-gateway \
  -v "$PWD":/plan -w /plan --user root alpine/jmeter \
  -n -t ipie-service-template-load-test.jmx -l out.jtl \
  -JappHost=ipie-user-service -JappPort=8080 -JkeycloakHost=keycloak -JkeycloakPort=8080 \
  -Jthreads=25 -Jloops=20 -Jrampup=10
```

Parameters: `threads`, `loops`, `rampup`, `appHost`, `appPort`, `keycloakHost`, `keycloakPort`, and
for the registration plan `mailHost`, `mailPort`, `mailWaitMs`.

## Two prerequisites for the registration plan

Neither is optional; without them the run measures the wrong thing and still reports "errors".

**1. Raise the registration rate limit — by the smallest amount that lets the run finish.**
`/api/v1/registrations/**` is capped at 5 requests per minute, deliberately: it is a public,
unauthenticated endpoint. Leave it in place and the plan measures 429s rather than the service.

Size the override to the run rather than disabling the limit — `threads × loops × 3` (the plan
makes three calls per iteration to that path) with a little headroom. For 30 threads × 2 loops:

```yaml
IPIE_RATE_LIMIT_REGISTRATIONS_LIMIT: 250
```

A number like 100000 is not a raised limit, it is a disabled one: the run then cannot tell you
whether the limiter still functions under load, and the setting is far more dangerous if it ever
escapes into an environment. Apply it through a throwaway compose override for the duration of the
run, never by editing the service's own configuration.

**2. Wire RabbitMQ.** The OTP email is delivered by ipie-communication-service in response to an
event. With `spring.rabbitmq.host` unset — the default — event publishing silently falls back to a
logging implementation, the outbox fills, no mail is ever delivered, and every iteration stops at
the confirm step. `docker-compose.services.yml` sets `SPRING_RABBITMQ_HOST: rabbitmq` for this
reason.

## Reading the result

For the registration plan the sampler that matters is **5 Complete registration**. Its failures are
the interesting signal:

- **5xx carrying `BulkheadFullException`** — the bulkhead's 10 concurrent calls per target is the
  binding limit. That is a rejection, not a queue: `max-wait-duration: 0` means the eleventh
  concurrent call fails immediately rather than waiting.
- **Latency climbing to ~15s** — retry is amplifying a slow dependency (3 attempts × 5s response
  timeout).
- **A sudden run of fast failures** — the circuit breaker opened, which is the system protecting
  itself rather than a fault in its own right.

Watch `tomcat_threads_busy` against `tomcat_threads_config_max` in Prometheus at the same time. That
is what distinguishes "the downstream is slow" from "this service is out of threads and requests are
queueing" — the two look identical in latency alone.

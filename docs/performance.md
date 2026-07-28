# Performance testing

> **Status.** Populated in Phase 12.

## The rule this document exists to enforce

**No performance number appears in this repository unless it was produced by an executed run, with
the hardware and date recorded next to it** (§0.3, §10).

The README states how to run the tests and where results are written. It does **not** state results
as fact unless a committed artifact backs them.

## Reference machine

Every number must cite the machine that produced it. This repository's reference machine
(from `docs/phase-reports/phase-00.md` §2):

| Property | Value |
|---|---|
| vCPU | 4 |
| RAM | 15 GiB |
| Kernel | Linux 6.18.5 |
| Storage driver | overlayfs |
| Notes | cgroup v1, **no cpuset support** |

**This is 4 cores, not the 6 the build specification assumes.**

## Scenarios (Phase 12, in `perf/`)

| Scenario | Purpose |
|---|---|
| smoke | Does it work at all under any load |
| ramp-up | Where does latency start degrading |
| steady-state | Sustained throughput |
| spike | Burst absorption; consumer and outbox lag rise and drain |
| soak (short) | Leak and drift detection |

Covering transaction ingestion (POST), the reconciliation grid query (heaviest read), and
Transaction 360 assembly.

## Thresholds

Defined as pass/fail gates (`http_req_failed < 1%`, `p(95) < X ms`), where **X is chosen after
measuring on the reference machine** — not picked in advance to look good.

## The caveat that must accompany every number here

On this machine, **k6 competes for the same 4 cores as the entire stack.** The numbers therefore
describe a contended sandbox, not system capacity. They must not be read as capacity figures or
extrapolated. This is stated in the results artifact itself, not only here.

## Tested limits, stated honestly

Single Kafka broker, single partition set, single instance per service, laptop-class hardware.

What would need to change to scale — partition count, consumer instances, read-model sharding,
CDC instead of outbox polling — is documented **without claiming any of it was tested**.

## Profiling path (Phase 12)

How to enable JFR, where to look for outbox-poller lag, how to spot consumer lag, and the three
most likely bottlenecks with reasoning.

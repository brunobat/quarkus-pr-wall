# Status for workflow `Quarkus CI`

This is the status report for running `Quarkus CI` on commit 1500000000000000000000000000000000000150.



:white_check_mark: The latest workflow run for the pull request has completed successfully.

---

## Failures

### :gear: JVM Tests - JDK 17

#### :package: extensions/grpc/deployment

<p>:x: <code>io.quarkus.grpc.client.InstanceInjectionTest.</code> - <a href="https://ge.quarkus.io/scans/tests?tests.container=io.quarkus.grpc.client.InstanceInjectionTest">History</a></p>

<details>

```
java.lang.RuntimeException: Failed to start quarkus
	at io.quarkus.test.QuarkusUnitTest.beforeAll(QuarkusUnitTest.java:705)
```

</details>

---

## Flaky tests - <a href="https://ge.quarkus.io/scans/tests">Develocity</a>

### :gear: JVM Tests - JDK 21

#### :package: extensions/resteasy-reactive/rest-jackson/deployment

<p>:x: <code>io.quarkus.resteasy.reactive.jackson.deployment.test.LargeDtoReflectionFreeTest</code> - <a href="https://ge.quarkus.io/scans/tests?tests.container=io.quarkus.resteasy.reactive.jackson.deployment.test.LargeDtoReflectionFreeTest">History</a></p>

 - `io.smallrye.mutiny.TimeoutException`

<details>

```
io.smallrye.mutiny.TimeoutException
	at io.smallrye.mutiny.operators.uni.UniBlockingAwait.await(UniBlockingAwait.java:64)
```

</details>

 - `io.smallrye.mutiny.TimeoutException`

<details>

```
io.smallrye.mutiny.TimeoutException
	at io.smallrye.mutiny.operators.uni.UniBlockingAwait.await(UniBlockingAwait.java:64)
```

</details>

<p>:x: <code>io.quarkus.resteasy.reactive.jackson.deployment.test.LargeDtoReflectionFreeTest</code> - <a href="https://ge.quarkus.io/scans/tests?tests.container=io.quarkus.resteasy.reactive.jackson.deployment.test.LargeDtoReflectionFreeTest">History</a></p>

 - `io.smallrye.mutiny.TimeoutException`

---

### :gear: JVM Tests - JDK 25

#### :package: extensions/resteasy-reactive/rest-jackson/deployment

<p>:x: <code>io.quarkus.resteasy.reactive.jackson.deployment.test.LargeDtoReflectionFreeTest</code> - <a href="https://ge.quarkus.io/scans/tests?tests.container=io.quarkus.resteasy.reactive.jackson.deployment.test.LargeDtoReflectionFreeTest">History</a></p>

 - `io.smallrye.mutiny.TimeoutException`

---

### :gear: JVM Tests - JDK 21 Windows

#### :package: extensions/resteasy-reactive/rest-jackson/deployment

<p>:x: <code>io.quarkus.resteasy.reactive.jackson.deployment.test.LargeDtoReflectionFreeTest</code> - <a href="https://ge.quarkus.io/scans/tests?tests.container=io.quarkus.resteasy.reactive.jackson.deployment.test.LargeDtoReflectionFreeTest">History</a></p>

 - `io.smallrye.mutiny.TimeoutException`

#### :package: extensions/vertx-http/deployment

<p>:x: <code>io.quarkus.vertx.http.filters.GracefulShutdownFilterTest.test</code> - <a href="https://ge.quarkus.io/scans/tests?tests.container=io.quarkus.vertx.http.filters.GracefulShutdownFilterTest&tests.test=test">History</a></p>

- `Timeout waiting for response` - `java.lang.RuntimeException`

### :gear: Native Tests - gRPC

<p>:x: <code>io.quarkus.grpc.client.InstanceInjectionTest.</code> - <a href="https://ge.quarkus.io/scans/tests?tests.container=io.quarkus.grpc.client.InstanceInjectionTest">History</a></p>

<!-- Build-Reporter/msg-id:workflow-run-status-active:Quarkus CI -->
<!-- Build-Reporter/workflow-run-id:41878251737 -->

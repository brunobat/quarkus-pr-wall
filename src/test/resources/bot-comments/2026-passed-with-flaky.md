# Status for workflow `Quarkus CI`

This is the status report for running `Quarkus CI` on commit a718653ee237e844bbf0d8d0b4c08915a17a9bd4.



:white_check_mark: The latest workflow run for the pull request has completed successfully.

It should be safe to merge provided you have a look at the other checks in the summary.

You can consult the [Develocity build scans](https://github.com/quarkusio/quarkus/runs/107885377256).


---

## Flaky tests - <a href="https://ge.quarkus.io/scans/tests">Develocity</a>

### :gear: JVM Tests - JDK 21 Windows

#### :package: extensions/micrometer/deployment

<p>:x: <code>io.quarkus.micrometer.deployment.binder.VertxTcpMetricsNoClientMetricsTest.testTcpMetricsWithoutClientMetrics</code> - <a href="https://ge.quarkus.io/scans/tests?tests.container=io.quarkus.micrometer.deployment.binder.VertxTcpMetricsNoClientMetricsTest&tests.test=testTcpMetricsWithoutClientMetrics">History</a></p>

- `Assertion condition defined as a Lambda expression in io.quarkus.micrometer.deployment.binder.VertxTcpMetricsNoClientMetricsTest expected: <1> but was: <0> within 10 seconds.` - `org.awaitility.core.ConditionTimeoutException`

<details>

```
org.awaitility.core.ConditionTimeoutException: Assertion condition defined as a Lambda expression in io.quarkus.micrometer.deployment.binder.VertxTcpMetricsNoClientMetricsTest expected: <1> but was: <0> within 10 seconds.
	at org.awaitility.core.ConditionAwaiter.await(ConditionAwaiter.java:167)
	at org.awaitility.core.AssertionCondition.await(AssertionCondition.java:119)
	at org.awaitility.core.AssertionCondition.await(AssertionCondition.java:31)
	at org.awaitility.core.ConditionFactory.until(ConditionFactory.java:1160)
	at org.awaitility.core.ConditionFactory.untilAsserted(ConditionFactory.java:790)
	at io.quarkus.micrometer.deployment.binder.VertxTcpMetricsNoClientMetricsTest.testTcpMetricsWithoutClientMetrics(VertxTcpMetricsNoClientMetricsTest.java:68)
Caused by: org.opentest4j.AssertionFailedError: expected: <1> but was: <0>
```

</details>



---

### :gear: JVM Integration Tests - JDK 25 Semeru

#### :package: integration-tests/rest-client

<p>:x: <code>io.quarkus.it.rest.client.selfsigned.ExternalSelfSignedTestCase.javaxNetSsl</code> - <a href="https://ge.quarkus.io/scans/tests?tests.container=io.quarkus.it.rest.client.selfsigned.ExternalSelfSignedTestCase&tests.test=javaxNetSsl">History</a></p>

- `1 expectation failed.
Expected status code <200> but was <500>.` - `java.lang.AssertionError`

<details>

```
java.lang.AssertionError: 
1 expectation failed.
Expected status code <200> but was <500>.

	at org.codehaus.groovy.vmplugin.v8.IndyInterface.fromCache(IndyInterface.java:344)
	at io.restassured.internal.ResponseSpecificationImpl$HamcrestAssertionClosure.validate(ResponseSpecificationImpl.groovy:516)
	at org.codehaus.groovy.vmplugin.v8.IndyInterface.fromCache(IndyInterface.java:344)
	at io.restassured.internal.ResponseSpecificationImpl.validateResponseIfRequired(ResponseSpecificationImpl.groovy:714)
```

</details>



<!-- Build-Reporter/msg-id:workflow-run-status-active:Quarkus CI -->
<!-- Build-Reporter/workflow-run-id:36064596652 -->

---
> :waning_crescent_moon: **_This workflow status is outdated as a new workflow run has been triggered._**
---

# Status for workflow `Quarkus CI`

This is the status report for running `Quarkus CI` on commit 4d456bf0dad6b5d357ea2d0a53bdb4f4ef00aec6.



## Failing Jobs



| Status | Name | Step | Failures | Logs | Raw logs | Build scan |
| :-:  | --  | --  | :-:  | :-:  | :-:  | :-:  |
| :x: | JVM Tests - JDK 21 | `Build` | [Failures](#user-content-test-failures-job-119740311156) | [Logs](https://github.com/quarkusio/quarkus/actions/runs/39751658302/job/119740311156) | [Raw logs](https://github.com/quarkusio/quarkus/commit/4d456bf0dad6b5d357ea2d0a53bdb4f4ef00aec6/checks/119740311156/logs) | [:mag:](https://develocity.quarkus.io/s/hzvs2pygcxpvk) |
| :x: | JVM Integration Tests - JDK 17 | `Build` | [Failures](#user-content-test-failures-job-119740311235) | [Logs](https://github.com/quarkusio/quarkus/actions/runs/39751658302/job/119740311235) | [Raw logs](https://github.com/quarkusio/quarkus/commit/4d456bf0dad6b5d357ea2d0a53bdb4f4ef00aec6/checks/119740311235/logs) | :construction: |
| :heavy_check_mark: | JVM Integration Tests - JDK 17 Windows |  |  | [Logs](https://github.com/quarkusio/quarkus/actions/runs/39751658302/job/119740311318) | [Raw logs](https://github.com/quarkusio/quarkus/commit/4d456bf0dad6b5d357ea2d0a53bdb4f4ef00aec6/checks/119740311318/logs) | [:mag:](https://develocity.quarkus.io/s/gfir72xr56l32) |

Full information is available in the [Build summary check run](https://github.com/quarkusio/quarkus/runs/119862417433).
You can consult the [Develocity build scans](https://github.com/quarkusio/quarkus/runs/119861810203).


## Failures

### :gear: JVM Tests - JDK 21 <a href="#user-content-test-failures-job-119740311156" id="test-failures-job-119740311156">#</a>

```diff
- Failing: extensions/websockets-next/deployment 

```

#### :package: extensions/websockets-next/deployment

<p>:x: <code>io.quarkus.websockets.next.test.maxframesize.MaxFrameSizeTest.testMaxFrameSize</code> line <code>46</code> - <a href="https://develocity.quarkus.io/scans/tests?tests.container=io.quarkus.websockets.next.test.maxframesize.MaxFrameSizeTest&tests.test=testMaxFrameSize">History</a> - <a href="https://github.com/quarkusio/quarkus/runs/119862417433#user-content-test-failure-io.quarkus.websockets.next.test.maxframesize.maxframesizetest-1">More details</a> - <a href="https://github.com/quarkusio/quarkus/blob/4d456bf0dad6b5d357ea2d0a53bdb4f4ef00aec6/extensions/websockets-next/deployment/src/test/java/io/quarkus/websockets/next/test/maxframesize/MaxFrameSizeTest.java#L46">Source on GitHub</a></p>

<details>

```
java.util.concurrent.CompletionException: io.netty.channel.StacklessClosedChannelException
	at java.base/java.util.concurrent.CompletableFuture.reportJoin(CompletableFuture.java:413)
	at java.base/java.util.concurrent.CompletableFuture.join(CompletableFuture.java:2118)
	at io.quarkus.websockets.next.test.utils.WSClient.await(WSClient.java:137)
	at io.quarkus.websockets.next.test.utils.WSClient.disconnect(WSClient.java:131)
	at io.quarkus.websockets.next.test.utils.WSClient.close(WSClient.java:164)
	at io.quarkus.websockets.next.test.maxframesize.MaxFrameSizeTest.testMaxFrameSize(MaxFrameSizeTest.java:46)
Caused by: io.netty.channel.StacklessClosedChannelException
```

</details>


---

<p>:x: <code>io.quarkus.vertx.http.filters.GracefulShutdownFilterTest.test</code> - <a href="https://develocity.quarkus.io/scans/tests?tests.container=io.quarkus.vertx.http.filters.GracefulShutdownFilterTest&tests.test=test">History</a></p>

- `Timeout waiting for response` - `java.lang.RuntimeException`

<details>

```
java.lang.RuntimeException: Timeout waiting for response
	at io.quarkus.vertx.http.filters.GracefulShutdownFilterTest.testWithVertxHttpClientAndHttp2AfterShutdown(GracefulShutdownFilterTest.java:85)
	at io.quarkus.vertx.http.filters.GracefulShutdownFilterTest.test(GracefulShutdownFilterTest.java:59)
```

</details>



### :gear: JVM Integration Tests - JDK 17 <a href="#user-content-test-failures-job-119740311235" id="test-failures-job-119740311235">#</a>

```diff
- Failing: integration-tests/kubernetes-client-devservices 

```

#### :package: integration-tests/kubernetes-client-devservices

<p>:x: <code>Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.4:test (default-test) on project quarkus-integration-test-kubernetes-client-devservices: 

See /home/runner/_work/quarkus/quarkus/integration-tests/kubernetes-client-devservices/target/surefire-reports for the individual test results.
See dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.
There was an error in the forked process</code></p>


---

## Flaky tests - <a href="https://develocity.quarkus.io/scans/tests">Develocity</a>

### :gear: JVM Tests - JDK 21

#### :package: extensions/vertx-http/deployment

<p>:x: <code>io.quarkus.vertx.http.filters.GracefulShutdownFilterTest.test</code> - <a href="https://develocity.quarkus.io/scans/tests?tests.container=io.quarkus.vertx.http.filters.GracefulShutdownFilterTest&tests.test=test">History</a></p>

- `Timeout waiting for response` - `java.lang.RuntimeException`

<details>

```
java.lang.RuntimeException: Timeout waiting for response
	at io.quarkus.vertx.http.filters.GracefulShutdownFilterTest.testWithVertxHttpClientAndHttp2AfterShutdown(GracefulShutdownFilterTest.java:85)
	at io.quarkus.vertx.http.filters.GracefulShutdownFilterTest.test(GracefulShutdownFilterTest.java:59)
```

</details>



---

### :gear: JVM Tests - JDK 17 Windows

#### :package: extensions/undertow/deployment

<p>:x: <code>io.quarkus.undertow.test.compress.CompressionEnabledTestCase.testCompressed</code> - <a href="https://develocity.quarkus.io/scans/tests?tests.container=io.quarkus.undertow.test.compress.CompressionEnabledTestCase&tests.test=testCompressed">History</a></p>

- `1 expectation failed.
Expected header "Content-Encoding" was not "gzip", was "null". Headers are:
Content-type=text/plain
Content-Length=2` - `java.lang.AssertionError`

<details>

```
java.lang.AssertionError: 
1 expectation failed.
Expected header "Content-Encoding" was not "gzip", was "null". Headers are:
Content-type=text/plain
Content-Length=2

	at java.base/jdk.internal.reflect.NativeConstructorAccessorImpl.newInstance0(Native Method)
	at java.base/jdk.internal.reflect.NativeConstructorAccessorImpl.newInstance(NativeConstructorAccessorImpl.java:77)
```

</details>



---

	at org.awaitility.core.CallableCondition.await(CallableCondition.java:78)
	at org.awaitility.core.CallableCondition.await(CallableCondition.java:26)
	a...
```

</details>



<!-- Build-Reporter/msg-id:workflow-run-status-hidden:Quarkus CI -->
<!-- Build-Reporter/workflow-run-id:39751658302 -->
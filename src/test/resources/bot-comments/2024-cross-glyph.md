# Status for workflow `Quarkus CI`

This is the status report for running `Quarkus CI` on commit e998f7923e1b8004df1665d8dc47f5cf3af971af.



## Failing Jobs



| Status | Name | Step | Failures | Logs | Raw logs | Build scan |
| :-:  | --  | --  | :-:  | :-:  | :-:  | :-:  |
| ✖ | Quickstarts Compilation - JDK 17 | `Compile Quickstarts` | [Failures](#user-content-test-failures-job-35187604258) | [Logs](https://github.com/quarkusio/quarkus/actions/runs/12272973293/job/35187604258) | [Raw logs](https://github.com/quarkusio/quarkus/commit/e998f7923e1b8004df1665d8dc47f5cf3af971af/checks/35187604258/logs) | :construction: |

You can consult the [Develocity build scans](https://github.com/quarkusio/quarkus/runs/35200432166).


## Failures

### :gear: Quickstarts Compilation - JDK 17 <a href="#user-content-test-failures-job-35187604258" id="test-failures-job-35187604258">#</a>

```diff
- Failing: security-webauthn-quickstart security-webauthn-reactive-quickstart 

```

#### :package: security-webauthn-quickstart

<p>✖ <code>Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.11.0:compile (default-compile) on project security-webauthn-quickstart: Compilation failure</code></p>

#### :package: security-webauthn-reactive-quickstart

<p>✖ <code>Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.11.0:compile (default-compile) on project security-webauthn-reactive-quickstart: Compilation failure</code></p>



---

## Flaky tests - <a href="https://ge.quarkus.io/scans/tests">Develocity</a>

### :gear: JVM Tests - JDK 21

#### :package: extensions/smallrye-reactive-messaging/deployment

<p>✖ <code>io.quarkus.smallrye.reactivemessaging.hotreload.ConnectorChangeTest.testUpdatingConnector</code> - <a href="https://ge.quarkus.io/scans/tests?tests.container=io.quarkus.smallrye.reactivemessaging.hotreload.ConnectorChangeTest&tests.test=testUpdatingConnector">History</a></p>

- `
Expecting actual:
  ["-4","-5","-6","-7","-8","-9","-10","-11"]
to start with:
  ["-3", "-4", "-5", "-6"]` - `java.lang.AssertionError`

<details>

```
java.lang.AssertionError: 

Expecting actual:
  ["-4","-5","-6","-7","-8","-9","-10","-11"]
to start with:
  ["-3", "-4", "-5", "-6"]

	at io.quarkus.smallrye.reactivemessaging.hotreload.ConnectorChangeTest.testUpdatingConnector(ConnectorChangeTest.java:36)
```

</details>



<!-- Build-Reporter/msg-id:workflow-run-status-active:Quarkus CI -->
<!-- Build-Reporter/workflow-run-id:12272973293 -->

# Agent prompt: fix Spring Batch ExecutionContext deserialization failure after a Boot 3→4 / Batch 5→6 upgrade

You are working in a Spring Batch project that has just been upgraded (or is
being upgraded) from Spring Boot 3.5.x (Spring Batch 5.x) to Spring Boot
4.0.x (Spring Batch 6.x), and job triggers are failing — or will fail —
against the pre-upgrade database with something like:

```
java.lang.IllegalArgumentException: Failed to deserialize object
	at org.springframework.batch.core.repository.dao.DefaultExecutionContextSerializer.deserialize(...)
	at org.springframework.batch.core.repository.dao.jdbc.JdbcExecutionContextDao.getExecutionContext(...)
	at org.springframework.batch.core.repository.explore.support.SimpleJobExplorer.getLastJobExecution(...)
	at org.springframework.batch.core.launch.support.SimpleJobOperator.startNextInstance(...)
Caused by: java.io.InvalidClassException: filter status: REJECTED
	at java.io.ObjectInputStream.filterCheck(...)
	at java.util.HashMap.readObject(...)
```

## Root cause (confirm before fixing anything)

This is **CVE-2026-47878**: `DefaultExecutionContextSerializer` was patched
(fixed in Spring Batch 6.0.5 and later; never fixed in the OSS 5.x line
past 5.2.6) to install a strict `ObjectInputFilter` before calling
`ObjectInputStream.readObject()`, instead of deserializing with no filter at
all. The filter only allows JDK built-ins and `org.springframework.batch.**`:

```
java.lang.*;java.util.*;java.util.concurrent.*;java.util.concurrent.atomic.*;java.util.concurrent.locks.*;java.time.*;java.time.chrono.*;java.time.zone.*;java.math.*;java.sql.*;java.net.URL;javax.xml.namespace.QName;org.springframework.batch.**;!*
```

Any application-defined class (custom POJOs, enums, DTOs — anything in the
project's own packages) stored in a job or step `ExecutionContext` **before**
the upgrade is now rejected on read, with `InvalidClassException: filter
status: REJECTED`.

### Step 1 — confirm this is actually the cause

Don't assume; verify:

1. Check the Spring Batch version before and after the upgrade (`mvn
   dependency:tree | grep spring-batch-core` or check the Spring Boot BOM's
   managed version). Confirm the "before" version is < 6.0.5 (or exactly
   5.0.0–5.2.6 if staying on the 5.x line) and the "after" version is
   ≥ 6.0.5.
2. Find what the job/step listeners actually put into the
   `ExecutionContext` (`stepExecution.getExecutionContext().put(...)`,
   `jobExecution.getExecutionContext().put(...)`, anywhere in the codebase).
   List every distinct class of value stored — not just the obvious ones;
   also check anything stored as a field *inside* another object (e.g. a
   custom class nested inside a `Map` or `List`), since one disallowed
   nested class fails the entire `readObject()` call for that whole context
   blob, not just that one key.
3. For any class found that isn't a JDK built-in, confirm independently that
   it's actually present in the existing database: decode one affected row
   and look at what's really in it before writing any fix. A good way to do
   this without touching the running app: write a small standalone
   (no-Spring) Java program that reads the `short_context` column (or
   `serialized_context` if longer than ~2500 chars) from
   `BATCH_JOB_EXECUTION_CONTEXT` / `BATCH_STEP_EXECUTION_CONTEXT` for the
   failing execution id, and deserializes it both (a) with the real
   `DefaultExecutionContextSerializer` (to reproduce the exact failure
   standalone) and (b) with a permissive, logging `ObjectInputFilter` that
   always returns `ALLOWED` and prints every class it's asked about — this
   tells you every class actually in that context, confirms exactly which
   one(s) are the problem, and is much faster to iterate on than restarting
   the full app each time.
   `DefaultExecutionContextSerializer.deserialize(InputStream)` expects the
   **raw Base64 text bytes** as input — it does `Base64.getDecoder().wrap(in)`
   internally — so don't pre-decode before passing it in.
4. Optionally, enable the JDK's own serialization-filter logging to get the
   exact rejected-class line without writing any code at all: a JUL
   `logging.properties` file with `java.io.serialization.level = FINER`
   (rejections only) or `FINEST` (every decision), loaded via
   `-Djava.util.logging.config.file=...` on the JVM that's deserializing.
   Note that if the app uses Logback (the Spring Boot default), Logback's
   JUL bridge may suppress this in the full app process — it reliably works
   in the standalone reproduction program from step 3, which has no Spring
   Boot logging bootstrap to fight with.

## Step 2 — two migration prerequisites, check these even if you already
## reproduced the bug, because they can mask it or block testing the fix

These aren't the reported bug, but they commonly show up in the same
upgrade and will confuse verification if not handled first:

- **Spring Boot 4's batch autoconfiguration only backs off in favor of a
  JDBC-backed job repository when it sees `@EnableBatchProcessing`** on the
  context — it does *not* look for `@EnableJdbcJobRepository` on its own.
  If the project doesn't already have `@EnableBatchProcessing` explicitly
  present, Boot silently wires a **resourceless, in-memory**
  `DefaultBatchConfiguration` and job triggers will "succeed" while writing
  nothing to the database at all (no error, no rows). If you see a job
  trigger return success but no new rows appear in `BATCH_JOB_EXECUTION`,
  this is almost certainly why. Fix: add both annotations together on the
  same `@Configuration`/`@SpringBootApplication` class:

  ```java
  @SpringBootApplication
  @EnableBatchProcessing
  @EnableJdbcJobRepository
  public class Application { ... }
  ```

- **`spring.batch.jdbc.initialize-schema` no longer exists in Spring Boot
  4** — `BatchProperties` only has `spring.batch.job.enabled` /
  `spring.batch.job.name` now, and there's no schema-initializer bean left
  in `BatchAutoConfiguration`. This only matters for *fresh* databases
  (new environments, integration tests with Testcontainers, etc.) — an
  existing production database's schema is unaffected and doesn't need to
  be recreated. For a schema migration on an *existing* database going from
  Batch 5.x to 6.x, use the official migration script shipped inside the
  `spring-batch-core` jar for the target DB vendor, at
  `org/springframework/batch/core/migration/6.0/migration-<vendor>.sql`
  (for Postgres this is a one-liner:
  `ALTER SEQUENCE BATCH_JOB_SEQ RENAME TO BATCH_JOB_INSTANCE_SEQ;`) — apply
  it once, directly against the database, before starting the upgraded app.
  Never hand-roll your own version of this script; extract and use the
  jar's copy so it's guaranteed correct for the exact Batch version in use.
  For a *fresh* database, apply the schema itself (e.g. via
  `spring.sql.init.schema-locations` pointed at
  `classpath:org/springframework/batch/core/schema-<vendor>.sql`, or
  Flyway/Liquibase if the project already uses one of those).

## Step 3 — apply the fix(es)

There are two complementary fixes. Apply **both**, in this order, unless
told otherwise:

### 3a. Allow-list serializer (the bridge — fixes reading existing data, ship this first)

Write a custom `ExecutionContextSerializer` that delegates `serialize()` to
the stock `DefaultExecutionContextSerializer` unchanged (so the on-disk
format and Base64 encoding stay exactly the same — never customize the
write path), but overrides `deserialize()` with a wider
`ObjectInputFilter` that additionally allows the project's own base
package(s):

```java
public class AllowListExecutionContextSerializer implements ExecutionContextSerializer {

    private static final String FILTER_PATTERN =
            "java.base/*;<YOUR_APP_BASE_PACKAGE>.**;!*";

    private final DefaultExecutionContextSerializer delegate = new DefaultExecutionContextSerializer();
    private final ObjectInputFilter allowListFilter = ObjectInputFilter.Config.createFilter(FILTER_PATTERN);

    @Override
    public void serialize(Map<String, Object> context, OutputStream out) throws IOException {
        delegate.serialize(context, out);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> deserialize(InputStream in) throws IOException {
        try (ObjectInputStream ois = new ObjectInputStream(java.util.Base64.getDecoder().wrap(in))) {
            ois.setObjectInputFilter(allowListFilter);
            return (Map<String, Object>) ois.readObject();
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Failed to deserialize object type", ex);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to deserialize object", ex);
        }
    }
}
```

Important details, don't skip these:
- `java.base/*` (not `java.base/**`) matches classes directly in `java.base`
  module packages one level deep — check which exact JDK modules/packages
  the context actually uses (step 1.3 above tells you) and widen the
  pattern precisely rather than reaching for a blanket `*` that disables
  the CVE fix's protection entirely. Prefer the *narrowest* pattern that
  covers what's really being stored.
- Scope the allow-listed application package(s) as tightly as possible —
  the exact model/DTO package(s) found in step 1.2, not the whole
  `com.yourcompany.**`.
- Add a log line in the class (constructor or first call) so it's visible
  at startup/first use that this custom serializer — not the default — is
  actually the one in effect. This matters: it's easy to wire this up wrong
  and have it silently fall back to the default, and then "fix verified"
  claims are false.

Wire it via `@EnableJdbcJobRepository`'s `executionContextSerializerRef`,
pointing at a bean name, with the bean registered alongside it:

```java
@EnableJdbcJobRepository(executionContextSerializerRef = "allowListExecutionContextSerializer")
public class Application {
    @Bean
    public AllowListExecutionContextSerializer allowListExecutionContextSerializer() {
        return new AllowListExecutionContextSerializer();
    }
}
```

(If the project configures the job repository a different way — a
`@Configuration` class extending `DefaultBatchConfiguration`, a
`JdbcJobRepositoryFactoryBean` bean directly, etc. — wire the serializer
through whichever mechanism is actually present; don't introduce a second,
conflicting repository configuration path.)

### 3b. Flatten the ExecutionContext (the structural fix — stop writing custom types going forward)

Find every place the project's listeners/steps write a non-JDK type into
the `ExecutionContext` and change it to write only JDK built-ins instead:

- A custom class with several fields → write each field as its own key
  (`String`, `long`/`Long`, `Instant`, `LocalDate`, `List<String>`, etc.)
  instead of the object itself.
- A custom enum → write `.name()` (a `String`) instead of the enum
  constant.
- A custom collection/wrapper type → unwrap it to a plain `HashMap`,
  `ArrayList`, etc. before storing.

This does **not** need the allow-list serializer at all — it's meant to
work with the stock, unmodified `DefaultExecutionContextSerializer` and its
default filter, with zero custom filter configuration. It only affects rows
written *after* the change; it does nothing for rows already in the
database (that's what 3a is for).

## Step 4 — verify, don't just assert

For each fix:

1. **Allow-list serializer**: trigger a job against the database's
   *existing*, pre-fix data (don't delete/reset anything first) and confirm
   it now succeeds, with the startup/first-use log line confirming the
   custom serializer is in play, not the default. Then trigger it again
   (reading back the row the fix itself just wrote) to confirm it's a
   durable fix, not a one-time fluke.
2. **Flattening**: clear or seed a clean baseline (the old rows still
   contain the old shape and will still fail regardless of this change —
   that's expected, not a regression), run two fresh triggers, and decode
   the newly-written context (the standalone reproduction tool from step
   1.3 works for this too) to confirm every value is now a JDK type.
3. Add both an automated integration test asserting the job runs
   successfully against a seeded, pre-fix-shaped `ExecutionContext` fixture
   (a real Base64 string captured from the actual pre-upgrade database, not
   hand-constructed), and a basic "job still runs end to end" test (e.g.
   Testcontainers against a real instance of whatever database the project
   uses) so this doesn't regress silently later.
4. Do **not** claim a fix works based on code review alone — actually run
   it against data shaped like the real failure and show the before/after
   HTTP status, log line, or test result.

## Do not do these things

- Do **not** suggest clearing/deleting the existing `*_EXECUTION_CONTEXT`
  rows as "the fix" — verify first that it's not (trigger twice: the first
  succeeds because there's nothing to deserialize yet, the second fails
  again because the still-unfixed code immediately writes the same
  disallowed type right back).
- Do **not** disable serialization filtering globally (no
  `-Djdk.serialFilter=` blanket allow, no replacing the filter with one that
  always returns `ALLOWED` in production code) — that's exactly the
  unsafe-deserialization hole CVE-2026-47878 patched. Scope the allow-list
  to the smallest pattern that actually covers what's being stored.
- Do **not** guess Spring Boot / Spring Batch / JUnit / Testcontainers
  version numbers — look them up (the project's existing `pom.xml`/
  `build.gradle`, or the relevant Maven Central / Spring Boot BOM) before
  changing any version.
- Do **not** assume `org.testcontainers:junit-jupiter` /
  `org.testcontainers:postgresql` artifact coordinates if the project is on
  Testcontainers 2.x (which ships with newer Spring Boot BOMs) — those
  artifacts were renamed to `org.testcontainers:testcontainers-junit-jupiter`
  / `org.testcontainers:testcontainers-postgresql` starting in
  Testcontainers 2.0.0. Check what version the project's BOM actually
  manages before assuming either naming scheme.

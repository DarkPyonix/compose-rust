# upcall-cost (experiment E0)

Measures what a call across the GraalVM native-image boundary costs on this machine, for
design PR #148 (`docs/design/graalvm-reuse-kn-window.md`, section 12).

- `src/Probe.java`: `@CEntryPoint` functions (empty, 32 byte struct), the parked-thread
  handshake loop, and a Java-side `@CFunction` downcall benchmark.
- `helper.c`: the plain C side (a trivial function, the condition-variable handshake and a
  pure C baseline of the same handshake). Built as its own dylib, like the window code.
- `driver.c`: creates the isolate and times everything.
- `run.sh`: builds (Liberica NIK 25 Full, `--parallelism=2`) and runs. Output goes to
  `build/`, which is ignored. Run it with `NATIVE_IMAGE_USER_HOME` and
  `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2` set inside the repository.
- `results.txt`: the two recorded runs (Apple M1, macOS 26).

Per-call numbers are batch means (1000 calls per batch, 2000 batches, after a warmup of
the same size), because the macOS clock ticks at 41.7 ns. The handshake is timed one round
trip at a time (100000 round trips after 5000 warmup).

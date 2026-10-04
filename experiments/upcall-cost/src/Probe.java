import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.word.Pointer;

/** Entry points and downcalls measured by experiment E0. */
public final class Probe {
    @CFunction(value = "probe_nop")
    static native int nop(int x);

    @CFunction(value = "hs_wait_request")
    static native int waitRequest();

    @CFunction(value = "hs_reply")
    static native void reply();

    // (a) upcall, empty.
    @CEntryPoint(name = "probe_empty")
    static void empty(IsolateThread thread) {
    }

    // (a) upcall with a 32 byte event struct, read the way the renderer reads dxc_event.
    @CEntryPoint(name = "probe_struct")
    static int struct(IsolateThread thread, Pointer e) {
        return e.readInt(0) + (int) e.readFloat(4) + (int) e.readFloat(8) + e.readInt(12)
                + e.readInt(16) + e.readInt(20) + e.readByte(24);
    }

    // (b) start the Java thread that parks inside waitRequest().
    @CEntryPoint(name = "probe_start_server")
    static void startServer(IsolateThread thread) {
        Thread t = new Thread(() -> {
            while (waitRequest() != 0) {
                reply();
            }
        }, "parked");
        t.setDaemon(true);
        t.start();
    }

    // (c) downcall cost, timed inside Java: out[0..2] = median, p99, max ns per call over
    // batches of `batch` calls, `batches` batches, after a warmup of the same size.
    @CEntryPoint(name = "probe_bench_downcall")
    static void benchDowncall(IsolateThread thread, Pointer out, int batches, int batch) {
        int sink = 0;
        for (int w = 0; w < batches; w++) {
            for (int i = 0; i < batch; i++) sink += nop(i);
        }
        double[] per = new double[batches];
        for (int b = 0; b < batches; b++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < batch; i++) sink += nop(i);
            per[b] = (System.nanoTime() - t0) / (double) batch;
        }
        java.util.Arrays.sort(per);
        out.writeDouble(0, per[batches / 2]);
        out.writeDouble(8, per[(int) (batches * 0.99)]);
        out.writeDouble(16, per[batches - 1]);
        out.writeInt(24, sink);
    }
}

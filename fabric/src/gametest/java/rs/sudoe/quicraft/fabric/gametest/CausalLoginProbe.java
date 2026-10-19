// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import rs.sudoe.quicraft.fabric.server.CausalSendContext;
import rs.sudoe.quicraft.fabric.mixin.ConnectionAccessor;

/** Test-only observer for the real W3a producer frames. */
public final class CausalLoginProbe {
    private static final String[] BOUNDARIES = {"login_spawn", "login_ready", "login"};
    private static volatile Attempt active;

    private CausalLoginProbe() {}

    public static Attempt arm(String faultName) {
        Attempt attempt = new Attempt(faultName);
        active = attempt;
        return attempt;
    }

    public static void disarm(Attempt attempt) {
        if (active == attempt) active = null;
    }

    public static void observe(String boundary, boolean head, Object connection) {
        Attempt attempt = active;
        if (attempt == null) return;
        try {
            if (!(connection instanceof net.minecraft.network.Connection mcConnection)) {
                throw new AssertionError("unexpected connection: " + connection);
            }
            var channel = ((ConnectionAccessor) mcConnection).quicraft$channel();
            CausalSendContext context = CausalSendContext.attached(channel);
            if (context == null) throw new AssertionError("no attached CausalSendContext at " + boundary);
            if (context.connection() != mcConnection) throw new AssertionError("context connection identity mismatch at " + boundary);
            var frames = ((rs.sudoe.quicraft.fabric.gametest.mixin.CausalLoginContextAccess) (Object) context)
                    .quicraft$producerFrames().get(Thread.currentThread());
            attempt.record(boundary, head, context, mcConnection, channel, Thread.currentThread(), frames);
        } catch (Throwable error) {
            attempt.error(boundary + " " + (head ? "HEAD" : "RETURN") + ": " + error);
        }
    }

    public static String[] expected(String faultName) {
        List<String> names = new ArrayList<>(List.of(BOUNDARIES));
        String removed = faultName == null ? null : switch (faultName) {
            case "causal login spawn" -> "login_spawn";
            case "causal login ready" -> "login_ready";
            case "causal place new player" -> "login";
            default -> throw new IllegalArgumentException("unknown fault name: " + faultName);
        };
        names.remove(removed);
        return names.toArray(String[]::new);
    }

    public static final class Attempt {
        public final String faultName;
        public final Map<String, Integer> heads = new java.util.LinkedHashMap<>();
        public final Map<String, Integer> returns = new java.util.LinkedHashMap<>();
        public final List<String> errors = new ArrayList<>();
        public final List<Event> events = new ArrayList<>();
        public CausalSendContext context;
        public net.minecraft.network.Connection connection;
        public io.netty.channel.Channel channel;
        public Thread owner;

        private Attempt(String faultName) { this.faultName = faultName; }

        public synchronized void error(String value) { errors.add(value); }

        private synchronized void record(String boundary, boolean head, CausalSendContext observed,
                net.minecraft.network.Connection mcConnection, io.netty.channel.Channel observedChannel,
                Thread thread, java.util.Deque<CausalSendContext.ProducerScope> stack) {
            if (!List.of(BOUNDARIES).contains(boundary)) throw new AssertionError("unknown boundary " + boundary);
            if (context != null && context != observed) throw new AssertionError("context identity changed");
            if (connection != null && connection != mcConnection) throw new AssertionError("connection identity changed");
            if (channel != null && channel != observedChannel) throw new AssertionError("channel identity changed");
            if (owner != null && owner != thread) throw new AssertionError("producer thread changed");
            context = observed;
            connection = mcConnection;
            channel = observedChannel;
            owner = thread;
            List<CausalSendContext.ProducerScope> identityStack = new ArrayList<>();
            List<String> names = new ArrayList<>();
            if (stack != null) {
                for (var it = stack.descendingIterator(); it.hasNext();) {
                    CausalSendContext.ProducerScope frame = it.next();
                    identityStack.add(frame);
                    names.add(frame.name());
                }
            }
            events.add(new Event(boundary, head, identityStack, names));
            (head ? heads : returns).merge(boundary, 1, Integer::sum);
        }

        public synchronized void validate() {
            String[] expected = expected(faultName);
            if (!errors.isEmpty()) throw new AssertionError("observer errors: " + errors);
            for (String boundary : BOUNDARIES) {
                if (heads.getOrDefault(boundary, 0) != 1 || returns.getOrDefault(boundary, 0) != 1)
                    throw new AssertionError("expected HEAD=1 RETURN=1 at " + boundary + "; heads=" + heads + " returns=" + returns);
            }
            List<String> sequence = List.of("login_spawn:HEAD", "login_ready:HEAD", "login:HEAD",
                    "login:RETURN", "login_ready:RETURN", "login_spawn:RETURN");
            if (!events.stream().map(Event::key).toList().equals(sequence))
                throw new AssertionError("event sequence=" + events.stream().map(Event::key).toList() + " expected=" + sequence);
            Map<String, CausalSendContext.ProducerScope> identityByName = new LinkedHashMap<>();
            for (Event event : events) {
                List<String> expectedAt = expectedAt(event.boundary);
                if (!event.names.equals(expectedAt)) {
                    String missing = expectedAt.stream().filter(n -> !event.names.contains(n)).findFirst().orElse("unexpected frame");
                    throw new AssertionError("missing frame " + missing + " at " + event.key() + ": actual=" + event.names + " expected=" + expectedAt);
                }
                for (int i = 0; i < expectedAt.size(); i++) {
                    String name = expectedAt.get(i);
                    CausalSendContext.ProducerScope frame = event.frames.get(i);
                    CausalSendContext.ProducerScope prior = identityByName.putIfAbsent(name, frame);
                    if (prior != null && prior != frame) throw new AssertionError("frame identity changed for " + name + " at " + event.key());
                    if (!frame.scopes().isEmpty() || !frame.tokens().isEmpty()) throw new AssertionError("frame refs not empty for " + name);
                }
            }
        }

        private List<String> expectedAt(String boundary) {
            List<String> all = switch (boundary) {
                case "login_spawn" -> List.of("login_spawn");
                case "login_ready" -> List.of("login_spawn", "login_ready");
                default -> List.of("login_spawn", "login_ready", "login");
            };
            List<String> result = new ArrayList<>(all);
            String removed = faultName == null ? null : switch (faultName) {
                case "causal login spawn" -> "login_spawn";
                case "causal login ready" -> "login_ready";
                default -> "login";
            };
            result.remove(removed);
            return result;
        }
        public synchronized String report() { return "fault=" + faultName + " connection=" + System.identityHashCode(connection) + " heads=" + heads + " returns=" + returns + " events=" + events + " errors=" + errors; }
    }

    public record Event(String boundary, boolean head, List<CausalSendContext.ProducerScope> frames,
            List<String> names) {
        public Event { frames = List.copyOf(frames); names = List.copyOf(names); }
        public String key() { return boundary + ":" + (head ? "HEAD" : "RETURN"); }
        @Override public String toString() { return boundary + ":" + (head ? "HEAD" : "RETURN") + names + "@" + frames.stream().map(System::identityHashCode).toList(); }
    }
}

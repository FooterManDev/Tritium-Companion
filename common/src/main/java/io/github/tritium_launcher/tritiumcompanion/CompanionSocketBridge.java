package io.github.tritium_launcher.tritiumcompanion;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.WebSocketServer;
import org.jetbrains.annotations.NotNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.Message;
import io.github.tritium_launcher.tritiumcompanion.client.options.OptionsExporter;

/**
 * Companion websocket bridge exposed by the mod for Tritium.
 * <ul> Hosts a websocket server on a configurable local endpoint. </ul>
 * <ul> Accepts JSON action requests and returns JSON responses. </ul>
 * <ul> Dispatches command actions onto the active Minecraft server thread. </ul>
 */
public final class CompanionSocketBridge
{
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final int DEFAULT_PORT = 38765;
    private static final int DEFAULT_COMMAND_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_RELOAD_TIMEOUT_MS = 10 * 60 * 1000;
    private static final int LEVEL_WAIT_TIMEOUT_MS = 2 * 60 * 1000;
    private static final int LEVEL_WAIT_POLL_MS = 200;
    private static final int MIN_COMMAND_TIMEOUT_MS = 1_000;
    private static final int MAX_COMMAND_TIMEOUT_MS = 15 * 60 * 1000;
    private static final String DEFAULT_BIND_HOST = "127.0.0.1";
    private static final String SOCKET_PATH = "/tritium";
    private static final String AUTH_HEADER = "X-Tritium-Token";
    private static final String ENV_AUTH_TOKEN = "TRITIUM_COMPANION_WS_TOKEN";
    private static final int MAX_TEXT_MESSAGE_BYTES = 1_048_576;
    private static final int RETRY_INITIAL_DELAY_MS = 1_000;
    private static final int RETRY_MAX_DELAY_MS = 60_000;

    private static final AtomicReference<MinecraftServer> ACTIVE_SERVER = new AtomicReference<>();
    private static final AtomicReference<BridgeServer> SERVER = new AtomicReference<>();
    private static final ConcurrentMap<String, ActionHandler> HANDLERS = new ConcurrentHashMap<>();
    private static final AtomicBoolean SHUTDOWN_HOOK_ADDED = new AtomicBoolean(false);
    private static final AtomicBoolean START_RETRY_SCHEDULED = new AtomicBoolean(false);
    private static final AtomicInteger START_RETRY_COUNT = new AtomicInteger(0);
    private static final AtomicInteger REQUEST_THREAD_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger ICON_THREAD_COUNTER = new AtomicInteger(1);
    private static final ExecutorService REQUEST_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            daemonThreadFactory("tritium-companion-ws-request-", REQUEST_THREAD_COUNTER)
    );
    /**
     * Single daemon thread that drives icon rendering. Icon GL work itself always runs on the
     * game's render thread (RegistryIconRenderer uses {@code RenderSystem.recordRenderCall}); this
     * thread is the *orchestrator* that submits those render calls and blocks on
     * {@code future.join()} per batch. Running it here — off the server thread — lets the icon
     * phase overlap with the data dumpers (which stay on the server thread) and, crucially, lets
     * the server keep ticking during the whole dump.
     */
    static final ExecutorService ICON_EXECUTOR = Executors.newSingleThreadExecutor(
            daemonThreadFactory("tritium-companion-icon-", ICON_THREAD_COUNTER)
    );
    private static final ScheduledExecutorService RETRY_EXECUTOR = Executors.newSingleThreadScheduledExecutor(
            daemonThreadFactory("tritium-companion-ws-retry-", new AtomicInteger(1))
    );

    private CompanionSocketBridge() {}

    @FunctionalInterface
    public interface ActionHandler
    {
        JsonObject handle(JsonObject payload) throws Exception;
    }

    public static void init() {
        registerBuiltinHandlers();
        startServer();
        addShutdownHook();
    }

    public static void setActiveServer(MinecraftServer server) {
        ACTIVE_SERVER.set(server);
    }

    public static void clearActiveServer(MinecraftServer server) {
        ACTIVE_SERVER.compareAndSet(server, null);
    }

    public static void registerHandler(String action, ActionHandler handler) {
        String normalizedAction = normalizeAction(action);
        HANDLERS.put(normalizedAction, handler);
    }
    /**
     * Broadcasts a JSON message to all connected websocket clients.
     */
    public static void broadcast(JsonObject message) {
        BridgeServer server = SERVER.get();
        if (server != null) {
            String json = GSON.toJson(message);
            for (WebSocket conn : server.getConnections()) {
                if (conn != null && conn.isOpen()) {
                    conn.send(json);
                }
            }
        }
    }

    private static void registerBuiltinHandlers() {
        registerHandler("ping", payload -> {
            JsonObject data = new JsonObject();
            data.addProperty("modId", TCompanion.MOD_ID);
            data.addProperty("protocolVersion", 1);
            data.addProperty("serverActive", ACTIVE_SERVER.get() != null);
            data.addProperty("socketPort", readPort());
            return data;
        });

        registerHandler("execute_command", payload -> {
            String command = requireText(payload, "command");
            int timeoutMs = readTimeoutMs(payload, DEFAULT_COMMAND_TIMEOUT_MS);
            if (command.equalsIgnoreCase("dumpRegistry")) {
                TritiumBuiltinApi.registerAll();
                awaitLevelLoadedIfNeeded(RegistryDumper.DumpScope.FULL);
            }
            return runServerCommand(command, timeoutMs);
        });

        registerHandler("dump_registry", payload -> {
            RegistryDumper.DumpScope scope = RegistryDumper.DumpScope.parse(payload);
            int timeoutMs = readTimeoutMs(payload, DEFAULT_COMMAND_TIMEOUT_MS);
            awaitLevelLoadedIfNeeded(scope);
            return runScopedDump(scope, timeoutMs);
        });

        registerHandler("reload_server", payload -> {
            int timeoutMs = readTimeoutMs(payload, DEFAULT_RELOAD_TIMEOUT_MS);
            JsonObject result = runServerCommand("reload", timeoutMs);
            JsonObject reloaded = new JsonObject();
            reloaded.addProperty("action", "server_reloaded");
            broadcast(reloaded);
            return result;
        });

        registerHandler("close_game", payload -> {
            int timeoutMs = readTimeoutMs(payload, DEFAULT_COMMAND_TIMEOUT_MS);
            return closeGame(timeoutMs);
        });

        registerHandler("get_options", payload -> {
            Minecraft mc = Minecraft.getInstance();
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            mc.execute(() -> {
                try {
                    future.complete(OptionsExporter.buildExport());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            try {
                return future.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new RuntimeException("Failed to export options", cause != null ? cause : e);
            }
        });

        registerHandler("command_suggestions", payload -> {
            String origInput = requireText(payload, "input");
            int origCursor = optInt(payload, "cursor", origInput.length());
            String input = origInput;
            int cursor = origCursor;

            if (input.startsWith("/")) {
                input = input.substring(1);
                if (cursor > 0) cursor--;
            }

            if (cursor > input.length()) {
                TCompanion.LOGGER.warn("command_suggestions clamped cursor {} -> {} for input len {} (orig input='{}' cursor={})",
                    cursor, input.length(), input.length(), origInput, origCursor);
                cursor = input.length();
            }

            MinecraftServer server = ACTIVE_SERVER.get();
            if (server == null) {
                throw new IllegalStateException("No active Minecraft server.");
            }

            CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
            CommandSourceStack source = server.createCommandSourceStack().withPermission(4);
            ParseResults<CommandSourceStack> parse = dispatcher.parse(input, source);
            Suggestions suggestions = dispatcher.getCompletionSuggestions(parse, cursor)
                .get(5_000, TimeUnit.MILLISECONDS);

            JsonObject data = new JsonObject();
            JsonArray list = new JsonArray();
            for (Suggestion s : suggestions.getList()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("text", s.getText());
                entry.addProperty("start", s.getRange().getStart());
                entry.addProperty("length", s.getRange().getLength());
                Message tooltip = s.getTooltip();
                if (tooltip != null) {
                    entry.addProperty("tooltip", tooltip.getString());
                }
                list.add(entry);
            }
            data.add("suggestions", list);
            return data;
        });
    }

    /**
     * Defer an icon-rendering dump until the client level has loaded. Icon rendering
     * (RegistryIconRenderer) requires {@code Minecraft.getInstance().level}; if a dump is
     * requested during world-load (before the player joins), it would otherwise publish an
     * empty icon set.
     *
     * <p>This MUST run on the {@code REQUEST_EXECUTOR} thread (a ws request handler), never
     * the server thread. While this thread sleeps, the server thread keeps ticking, so the
     * client can finish joining and create {@code mc.level}. Blocking the server thread
     * instead would deadlock (the client can only complete its join when the server ticks).
     *
     * <p>{@code section} scopes (recipes+tags only) never render icons, so they are not gated.
     * If a level still does not appear within {@link #LEVEL_WAIT_TIMEOUT_MS}, we proceed anyway
     * and the icon phase will be skipped gracefully (the launcher treats the dump as complete).
     */
    private static void awaitLevelLoadedIfNeeded(RegistryDumper.DumpScope scope) {
        if (scope != null && scope.getType().equals("section")) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            return;
        }
        TCompanion.LOGGER.info("Registry dump requested before a level was loaded; deferring until the client joins the world...");
        long deadline = System.currentTimeMillis() + LEVEL_WAIT_TIMEOUT_MS;
        while (mc.level == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(LEVEL_WAIT_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                TCompanion.LOGGER.warn("Interrupted while waiting for a level to load; proceeding with dump (icons may be skipped).");
                return;
            }
        }
        if (mc.level == null) {
            TCompanion.LOGGER.warn("No level loaded after {} ms; proceeding with dump without icons.", LEVEL_WAIT_TIMEOUT_MS);
        } else {
            TCompanion.LOGGER.info("Level loaded; proceeding with registry dump.");
        }
    }

    /**
     * Triggers a client resource reload so {@code Minecraft.getInstance().getResourceManager()}
     * serves freshly-edited pack bytes (textures/models) instead of the cached copies.
     *
     * <p>This MUST run on the client thread. {@code reloadResourcePacks()} is dispatched onto it
     * and the returned future is awaited on the {@code REQUEST_EXECUTOR} thread so the subsequent
     * textures dump reads fresh bytes. We never block the server thread.
     */
    private static void reloadClientResources() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<Void> reload = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                mc.reloadResourcePacks().whenComplete((v, t) -> {
                    if (t != null) reload.completeExceptionally(t);
                    else reload.complete(v);
                });
            } catch (Throwable t) {
                reload.completeExceptionally(t);
            }
        });
        try {
            reload.get();
        } catch (ExecutionException e) {
            throw new Exception("Failed to reload client resources for textures dump", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
        TCompanion.LOGGER.info("Client resources reloaded for textures-scoped dump.");
    }

    private static JsonObject runScopedDump(RegistryDumper.DumpScope scope, int timeoutMs) throws Exception {
        int boundedTimeoutMs = clampTimeoutMs(timeoutMs);
        MinecraftServer server = ACTIVE_SERVER.get();
        if (server == null) {
            throw new IllegalStateException("No active Minecraft server. Load into a world first.");
        }

        if (scope.getType().equals("textures")) {
            reloadClientResources();
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                CommandSourceStack source = server.createCommandSourceStack().withPermission(4);
                Command.runScopedDump(server, source, scope);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            future.get(boundedTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Timed out waiting for scoped registry dump after " + boundedTimeoutMs + "ms.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }

        JsonObject data = new JsonObject();
        data.addProperty("scope", scope.getType());
        data.addProperty("timeoutMs", boundedTimeoutMs);
        return data;
    }

    private static JsonObject runServerCommand(String rawCommand, int timeoutMs) throws Exception {
        String command = normalizeCommand(rawCommand);
        int boundedTimeoutMs = clampTimeoutMs(timeoutMs);
        MinecraftServer server = ACTIVE_SERVER.get();
        if (server == null) {
            throw new IllegalStateException("No active Minecraft server. Load into a world first.");
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                CommandSourceStack source = server.createCommandSourceStack().withPermission(4);
                server.getCommands().performPrefixedCommand(source, command);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            future.get(boundedTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Timed out waiting for command dispatch after " + boundedTimeoutMs + "ms.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }

        JsonObject data = new JsonObject();
        data.addProperty("command", command);
        data.addProperty("dispatchTimeoutMs", boundedTimeoutMs);
        return data;
    }

    /**
     * Requests game shutdown on the client runtime.
     */
    private static JsonObject closeGame(int timeoutMs) throws Exception {
        int boundedTimeoutMs = clampTimeoutMs(timeoutMs);
        JsonObject data = new JsonObject();
        data.addProperty("timeoutMs", boundedTimeoutMs);

        MinecraftServer server = ACTIVE_SERVER.get();
        if (server != null) {
            try {
                runServerFlushSave(server, boundedTimeoutMs);
                data.addProperty("serverSaveAttempted", true);
            } catch (Throwable t) {
                TCompanion.LOGGER.error("Server save failed before shutdown; aborting client exit", t);
                throw new IllegalStateException("Server save failed before shutdown.", t);
            }
        } else {
            data.addProperty("serverSaveSkipped", true);
            data.addProperty("serverSaveSkipReason", "no_active_server");
        }

        shutdownClient(boundedTimeoutMs);
        data.addProperty("clientShutdownRequested", true);
        return data;
    }

    private static void runServerFlushSave(MinecraftServer server, int timeoutMs) throws Exception {
        int boundedTimeoutMs = clampTimeoutMs(timeoutMs);

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                boolean ok = server.saveEverything(false, true, true);
                future.complete(ok);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            Boolean result = future.get(boundedTimeoutMs, TimeUnit.MILLISECONDS);
            if (result == null || !result) {
                throw new IllegalStateException("Server save operation reported failure.");
            }
        } catch (TimeoutException e) {
            throw new IllegalStateException("Timed out waiting for server save (flush) after " + boundedTimeoutMs + "ms.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private static void startServer() {
        queueStartAttempt(0L);
    }

    private static void queueStartAttempt(long delayMs) {
        if (SERVER.get() != null) return;
        if (delayMs <= 0L) {
            startServerAttempt();
            return;
        }
        if (!START_RETRY_SCHEDULED.compareAndSet(false, true)) return;
        RETRY_EXECUTOR.schedule(() -> {
            START_RETRY_SCHEDULED.set(false);
            startServerAttempt();
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private static void startServerAttempt() {
        if (SERVER.get() != null) return;
        int port = readPort();
        String bindHost = readBindHost();

        BridgeServer bridgeServer = new BridgeServer(bindHost, port);
        if (!SERVER.compareAndSet(null, bridgeServer)) return;

        try {
            bridgeServer.startServer();
        } catch (Throwable t) {
            SERVER.compareAndSet(bridgeServer, null);
            scheduleStartupRetry(bindHost, port, t);
        }
    }

    private static void scheduleStartupRetry(String bindHost, int port, Throwable t) {
        int attempt = START_RETRY_COUNT.incrementAndGet();
        long delayMs = computeRetryDelayMs(attempt);
        TCompanion.LOGGER.warn(
                "Failed to start companion websocket bridge on ws://{}:{}{} (attempt {}). Retrying in {}ms.",
                bindHost,
                port,
                SOCKET_PATH,
                attempt,
                delayMs,
                t
        );
        queueStartAttempt(delayMs);
    }

    private static long computeRetryDelayMs(int attempt) {
        int safeAttempt = Math.max(1, attempt);
        int shift = Math.min(safeAttempt - 1, 6);
        long backoff = RETRY_INITIAL_DELAY_MS * (1L << shift);
        return Math.min(backoff, RETRY_MAX_DELAY_MS);
    }

    private static void addShutdownHook() {
        if (!SHUTDOWN_HOOK_ADDED.compareAndSet(false, true)) return;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            BridgeServer bridgeServer = SERVER.getAndSet(null);
            if (bridgeServer != null) {
                bridgeServer.stopServer();
            }
            REQUEST_EXECUTOR.shutdownNow();
            RETRY_EXECUTOR.shutdownNow();
            ICON_EXECUTOR.shutdownNow();
        }, "tritium-companion-ws-shutdown"));
    }

    private static int readPort() {
        String raw = System.getProperty("tritium.companion.ws.port", String.valueOf(DEFAULT_PORT)).trim();
        int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            TCompanion.LOGGER.warn("Invalid tritium.companion.ws.port '{}'. Falling back to {}.", raw, DEFAULT_PORT);
            return DEFAULT_PORT;
        }

        if (parsed < 1 || parsed > 65535) {
            TCompanion.LOGGER.warn("Out-of-range tritium.companion.ws.port '{}'. Falling back to {}.", raw, DEFAULT_PORT);
            return DEFAULT_PORT;
        }
        return parsed;
    }

    private static String readBindHost() {
        String raw = System.getProperty("tritium.companion.ws.bind", DEFAULT_BIND_HOST).trim();
        if (raw.isEmpty()) return DEFAULT_BIND_HOST;
        return raw;
    }

    private static boolean allowRemoteConnections() {
        return Boolean.parseBoolean(System.getProperty("tritium.companion.ws.allow_remote", "false"));
    }

    private static boolean requireAuthToken() {
        return Boolean.parseBoolean(System.getProperty("tritium.companion.ws.require_token", "true"));
    }

    private static String readAuthToken() {
        String fromProperty = System.getProperty("tritium.companion.ws.token", "").trim();
        if (!fromProperty.isEmpty()) return fromProperty;
        String fromEnv = System.getenv(ENV_AUTH_TOKEN);
        return fromEnv == null ? "" : fromEnv.trim();
    }

    private static boolean isAllowedClient(InetSocketAddress remote) {
        if (allowRemoteConnections()) return false;
        if (remote == null) return true;
        InetAddress address = remote.getAddress();
        if (address == null) return true;
        return !address.isLoopbackAddress() && !address.isAnyLocalAddress();
    }

    private static boolean hasValidAuthToken(ClientHandshake request) {
        if (!requireAuthToken()) return false;
        String expected = readAuthToken();
        if (expected.isBlank()) return true;
        String provided = request == null ? "" : request.getFieldValue(AUTH_HEADER);
        if (provided == null || provided.isBlank()) return true;
        return !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.trim().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static boolean isExpectedPath(String path) {
        if (path == null) return true;
        return !SOCKET_PATH.equals(path) && !path.startsWith(SOCKET_PATH + "?");
    }

    private static int readTimeoutMs(JsonObject payload, int fallbackMs) {
        if (payload == null || !payload.has("timeoutMs")) return fallbackMs;
        JsonElement timeoutElement = payload.get("timeoutMs");
        if (timeoutElement == null || !timeoutElement.isJsonPrimitive()) return fallbackMs;
        try {
            if (timeoutElement.getAsJsonPrimitive().isNumber()) {
                return clampTimeoutMs(timeoutElement.getAsInt());
            }
            if (timeoutElement.getAsJsonPrimitive().isString()) {
                String raw = timeoutElement.getAsString().trim();
                if (raw.isEmpty()) return fallbackMs;
                return clampTimeoutMs(Integer.parseInt(raw));
            }
        } catch (Throwable ignored) {
            return fallbackMs;
        }
        return fallbackMs;
    }

    private static int clampTimeoutMs(int timeoutMs) {
        if (timeoutMs < MIN_COMMAND_TIMEOUT_MS) return MIN_COMMAND_TIMEOUT_MS;
        return Math.min(timeoutMs, MAX_COMMAND_TIMEOUT_MS);
    }

    private static void shutdownClient(int timeoutMs) throws Exception {
        int boundedTimeoutMs = clampTimeoutMs(timeoutMs);
        CompletableFuture<Void> future = queueClientShutdown();

        try {
            future.get(boundedTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Timed out waiting to request client shutdown after " + boundedTimeoutMs + "ms.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private static @NotNull CompletableFuture<Void> queueClientShutdown() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        final Minecraft minecraftClient = Minecraft.getInstance();

        minecraftClient.execute(() -> {
            try {
                minecraftClient.stop();
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private static JsonObject handleRequest(JsonObject request) {
        String requestId = optText(request, "id");
        final String actionName;
        try {
            actionName = normalizeAction(optText(request, "action"));
        } catch (IllegalArgumentException e) {
            return errorResponse(requestId, e.getMessage());
        }

        ActionHandler handler = HANDLERS.get(actionName);
        if (handler == null) {
            return errorResponse(requestId, "Unknown action '" + actionName + "'.");
        }

        JsonObject payload = readPayload(request);
        try {
            JsonObject data = handler.handle(payload);
            return successResponse(requestId, "ok", data);
        } catch (Throwable t) {
            TCompanion.LOGGER.warn("Websocket action '{}' failed", actionName, t);
            String msg = t.getMessage();
            if (msg == null || msg.isBlank()) msg = "Request failed.";
            return errorResponse(requestId, msg);
        }
    }

    private static JsonObject readPayload(JsonObject request) {
        if (request == null || !request.has("payload")) return new JsonObject();
        JsonElement payload = request.get("payload");
        if (payload == null || !payload.isJsonObject()) return new JsonObject();
        return payload.getAsJsonObject();
    }

    private static JsonObject successResponse(String id, String message, JsonObject data) {
        JsonObject response = baseResponse(id, true, message);
        response.add("data", data != null ? data : new JsonObject());
        return response;
    }

    private static JsonObject errorResponse(String id, String message) {
        JsonObject response = baseResponse(id, false, message);
        response.add("data", new JsonObject());
        return response;
    }

    private static JsonObject baseResponse(String id, boolean ok, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("id", id == null ? "" : id);
        response.addProperty("ok", ok);
        response.addProperty("message", message == null ? "" : message);
        return response;
    }

    private static String normalizeAction(String raw) {
        if (raw == null) throw new IllegalArgumentException("Missing 'action'.");
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) throw new IllegalArgumentException("Missing 'action'.");
        return normalized;
    }

    private static String normalizeCommand(String raw) {
        if (raw == null) throw new IllegalArgumentException("Missing command.");
        String command = raw.trim();
        if (command.startsWith("/")) command = command.substring(1);
        if (command.isBlank()) throw new IllegalArgumentException("Missing command.");
        return command;
    }

    private static String requireText(JsonObject payload, String key) {
        String value = optText(payload, key).trim();
        if (value.isBlank()) throw new IllegalArgumentException("Missing '" + key + "' value.");
        return value;
    }

    private static String optText(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key)) return "";
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) return "";
        if (!element.getAsJsonPrimitive().isString()) return "";
        return element.getAsString();
    }

    private static int optInt(JsonObject object, String key, int fallback) {
        if (object == null || key == null || !object.has(key)) return fallback;
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        try {
            return element.getAsJsonPrimitive().getAsInt();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static ThreadFactory daemonThreadFactory(String prefix, AtomicInteger counter) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class BridgeServer extends WebSocketServer
    {
        private final String bindHost;
        private final int port;
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean startupFailureHandled = new AtomicBoolean(false);

        private BridgeServer(String bindHost, int port) {
            super(
                    new InetSocketAddress(bindHost, port),
                    Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                    List.of(new Draft_6455(Collections.emptyList(), MAX_TEXT_MESSAGE_BYTES))
            );
            this.bindHost = bindHost;
            this.port = port;
            setDaemon(true);
            setReuseAddr(true);
            setTcpNoDelay(true);
            setConnectionLostTimeout(30);
        }

        private void startServer() {
            start();
        }

        private void stopServer() {
            try {
                stop(1_000, "shutdown");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                TCompanion.LOGGER.warn("Failed to stop companion websocket cleanly", t);
            }
        }

        @Override
        public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket conn, Draft draft, ClientHandshake request) throws InvalidDataException {
            String resource = request == null ? null : request.getResourceDescriptor();
            if (isExpectedPath(resource)) {
                throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "Unknown websocket path.");
            }

            InetSocketAddress remote = conn == null ? null : conn.getRemoteSocketAddress();
            if (isAllowedClient(remote)) {
                throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "Remote websocket clients are disabled.");
            }

            if (hasValidAuthToken(request)) {
                throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "Unauthorized websocket client.");
            }

            return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            String path = handshake == null ? "" : handshake.getResourceDescriptor();
            if (isExpectedPath(path)) {
                conn.close(CloseFrame.POLICY_VALIDATION, "Unknown websocket path.");
                return;
            }

            InetSocketAddress remote = conn.getRemoteSocketAddress();
            if (isAllowedClient(remote)) {
                conn.close(CloseFrame.POLICY_VALIDATION, "Remote websocket clients are disabled.");
                return;
            }

            if (hasValidAuthToken(handshake)) {
                conn.close(CloseFrame.POLICY_VALIDATION, "Unauthorized websocket client.");
                return;
            }

            TCompanion.LOGGER.debug("Companion websocket client connected: {}", remote);
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            TCompanion.LOGGER.debug(
                    "Companion websocket client disconnected: {} (code={}, reason='{}', remote={})",
                    safeRemote(conn),
                    code,
                    reason,
                    remote
            );
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            if (message == null) {
                sendResponse(conn, errorResponse("", "Payload must be a JSON object."));
                return;
            }

            if (message.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_MESSAGE_BYTES) {
                conn.close(CloseFrame.TOOBIG, "Payload is too large.");
                return;
            }

            final JsonObject request;
            try {
                JsonElement parsed = JsonParser.parseString(message);
                if (!parsed.isJsonObject()) {
                    sendResponse(conn, errorResponse("", "Payload must be a JSON object."));
                    return;
                }
                request = parsed.getAsJsonObject();
            } catch (JsonParseException e) {
                sendResponse(conn, errorResponse("", "Invalid JSON payload."));
                return;
            } catch (Throwable t) {
                TCompanion.LOGGER.warn("Unexpected websocket bridge error", t);
                sendResponse(conn, errorResponse("", "Unexpected server error."));
                return;
            }

            try {
                REQUEST_EXECUTOR.execute(() -> {
                    JsonObject response;
                    try {
                        response = handleRequest(request);
                    } catch (Throwable t) {
                        TCompanion.LOGGER.warn("Unexpected websocket bridge error", t);
                        response = errorResponse("", "Unexpected server error.");
                    }
                    sendResponse(conn, response);
                });
            } catch (RejectedExecutionException e) {
                sendResponse(conn, errorResponse("", "Server is shutting down."));
            }
        }

        @Override
        public void onMessage(WebSocket conn, ByteBuffer message) {
            conn.close(CloseFrame.REFUSE, "Only text frames are supported.");
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            if (conn == null) {
                if (!started.get()) {
                    if (startupFailureHandled.compareAndSet(false, true)) {
                        SERVER.compareAndSet(this, null);
                        scheduleStartupRetry(bindHost, port, ex);
                    }
                } else {
                    TCompanion.LOGGER.warn("Companion websocket server error", ex);
                }
                return;
            }

            TCompanion.LOGGER.warn("Companion websocket connection error for {}", safeRemote(conn), ex);
        }

        @Override
        public void onStart() {
            started.set(true);
            startupFailureHandled.set(true);
            START_RETRY_COUNT.set(0);
            START_RETRY_SCHEDULED.set(false);
            if (requireAuthToken() && readAuthToken().isBlank()) {
                TCompanion.LOGGER.warn("Companion websocket token auth is enabled but no token is configured. All clients will be rejected.");
            }
            TCompanion.LOGGER.info("Companion websocket listening on ws://{}:{}{}", bindHost, port, SOCKET_PATH);
        }

        private void sendResponse(WebSocket conn, JsonObject response) {
            if (conn == null || response == null || !conn.isOpen()) return;

            try {
                conn.send(GSON.toJson(response));
            } catch (Throwable t) {
                TCompanion.LOGGER.warn("Failed to send websocket response to {}", safeRemote(conn), t);
            }
        }

        private static String safeRemote(WebSocket conn) {
            if (conn == null) return "unknown";
            try {
                return String.valueOf(conn.getRemoteSocketAddress());
            } catch (Throwable ignored) {
                return "unknown";
            }
        }
    }
}

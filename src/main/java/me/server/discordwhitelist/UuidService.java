package me.server.discordwhitelist;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class UuidService {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    public static record ProfileResult(UUID uuid, String exactName, boolean isOfficialMojang) {}

    public static UUID offlineUUID(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID parseMojangUuid(String id) {
        if (id == null || id.length() != 32) {
            throw new IllegalArgumentException("Invalid Mojang UUID string: " + id);
        }
        long mostSigBits = Long.parseUnsignedLong(id.substring(0, 16), 16);
        long leastSigBits = Long.parseUnsignedLong(id.substring(16, 32), 16);
        return new UUID(mostSigBits, leastSigBits);
    }

    public static boolean isValidUsername(String username, boolean allowBedrock) {
        if (username == null || username.isBlank() || username.length() > 16) {
            return false;
        }
        if (allowBedrock && (username.startsWith(".") || username.startsWith("*"))) {
            return username.substring(1).matches("^[A-Za-z0-9_ ]{1,15}$");
        }
        return username.matches("^[A-Za-z0-9_]{3,16}$");
    }

    public static CompletableFuture<ProfileResult> lookupMojang(String username) {
        try {
            String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
            String url = "https://api.mojang.com/users/profiles/minecraft/" + encoded;
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "DiscordWhitelist-PaperPlugin")
                .GET()
                .build();

            return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    int code = response.statusCode();
                    if (code == 200) {
                        try {
                            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                            String id = json.get("id").getAsString();
                            String name = json.get("name").getAsString();
                            return new ProfileResult(parseMojangUuid(id), name, true);
                        } catch (Exception e) {
                            return null;
                        }
                    }
                    return null;
                })
                .exceptionally(ex -> null);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(null);
        }
    }

    public static CompletableFuture<ProfileResult> resolveProfile(String username, String mode, boolean allowBedrock) {
        if (!isValidUsername(username, allowBedrock)) {
            return CompletableFuture.completedFuture(null);
        }

        // Bedrock/Floodgate users with prefix '.' or '*'
        if (allowBedrock && (username.startsWith(".") || username.startsWith("*"))) {
            UUID uuid = offlineUUID(username);
            return CompletableFuture.completedFuture(new ProfileResult(uuid, username, false));
        }

        String normalizedMode = mode == null ? "auto" : mode.toLowerCase().trim();

        if ("offline".equals(normalizedMode)) {
            UUID uuid = offlineUUID(username);
            return CompletableFuture.completedFuture(new ProfileResult(uuid, username, false));
        }

        if ("mojang".equals(normalizedMode)) {
            return lookupMojang(username);
        }

        // "auto" mode: check Mojang API first; fallback to offline UUID if not found or on error
        return lookupMojang(username).thenApply(official -> {
            if (official != null) {
                return official;
            }
            UUID uuid = offlineUUID(username);
            return new ProfileResult(uuid, username, false);
        });
    }
}

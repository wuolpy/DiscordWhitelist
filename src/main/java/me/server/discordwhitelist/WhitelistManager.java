package me.server.discordwhitelist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public class WhitelistManager {
   private final DiscordWhitelistPlugin plugin;
   private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
   private final File whitelistJsonFile;
   private final File discordDataFile;
   private final Map<String, DiscordEntry> discordUsers = new ConcurrentHashMap<>();

   public WhitelistManager(DiscordWhitelistPlugin plugin) {
      this.plugin = plugin;
      this.whitelistJsonFile = new File(plugin.getServer().getWorldContainer(), "whitelist.json");
      if (!plugin.getDataFolder().exists()) {
         plugin.getDataFolder().mkdirs();
      }

      this.discordDataFile = new File(plugin.getDataFolder(), "discord_whitelist_data.json");
      this.loadDiscordUsers();
   }

   public CompletableFuture<Result> whitelistPlayerAsync(String discordId, String discordName, String username, boolean force) {
      boolean allowBedrock = this.plugin.getConfig().getBoolean("settings.allow-bedrock-prefix", true);
      String uuidMode = this.plugin.getConfig().getString("settings.uuid-mode", "auto");

      if (!UuidService.isValidUsername(username, allowBedrock)) {
         return CompletableFuture.completedFuture(new Result(false, this.msg("invalid-username", "That's not a valid Minecraft username.")));
      }

      if (!force && this.discordUsers.containsKey(discordId)) {
         DiscordEntry existing = this.discordUsers.get(discordId);
         String msg = this.msg("already-used", "You've already whitelisted the account %username%.")
            .replace("%username%", existing.minecraftUsername());
         return CompletableFuture.completedFuture(new Result(false, msg, existing, null));
      }

      DiscordEntry nameConflict = this.getEntryByMinecraftName(username);
      if (nameConflict != null && !nameConflict.discordId().equals(discordId)) {
         String msg = this.msg("already-whitelisted-name", "That username is already linked to another Discord user.")
            .replace("%username%", username);
         return CompletableFuture.completedFuture(new Result(false, msg));
      }

      return UuidService.resolveProfile(username, uuidMode, allowBedrock).thenApply(profile -> {
         if (profile == null) {
            String notFound = this.msg("mojang-account-not-found", "Could not find a Mojang account with username %username%.")
               .replace("%username%", username);
            return new Result(false, notFound);
         }

         DiscordEntry uuidConflict = this.getEntryByUuid(profile.uuid().toString());
         if (uuidConflict != null && !uuidConflict.discordId().equals(discordId)) {
            String msg = this.msg("already-whitelisted-name", "That username/UUID is already whitelisted by someone else.")
               .replace("%username%", profile.exactName());
            return new Result(false, msg);
         }

         // Schedule sync updates on the Bukkit main thread
         this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
            try {
               OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(profile.uuid());
               offlinePlayer.setWhitelisted(true);
               this.updateWhitelistJsonAdd(profile.uuid(), profile.exactName());
               Bukkit.reloadWhitelist();
            } catch (Exception e) {
               this.plugin.getLogger().warning("Failed to sync whitelist.json: " + e.getMessage());
            }
         });

         DiscordEntry entry = new DiscordEntry(
            discordId,
            discordName,
            profile.exactName(),
            profile.uuid().toString(),
            System.currentTimeMillis()
         );
         this.discordUsers.put(discordId, entry);
         this.saveDiscordUsers();

         String serverIp = this.plugin.getConfig().getString("server-ip", "play.yourserver.com");
         String successMsg = this.msg("success", "You're whitelisted as **%username%**! Launch Minecraft and join %server_ip%.")
            .replace("%username%", profile.exactName())
            .replace("%server_ip%", serverIp);

         return new Result(true, successMsg, entry, profile);
      });
   }

   public CompletableFuture<Result> unwhitelistAsync(String target, String executorName) {
      DiscordEntry entry = this.lookup(target);
      if (entry == null) {
         return CompletableFuture.completedFuture(new Result(false, "Could not find a whitelist record for `" + target + "`."));
      }

      UUID uuid;
      try {
         uuid = UUID.fromString(entry.uuid());
      } catch (Exception e) {
         uuid = UuidService.offlineUUID(entry.minecraftUsername());
      }

      final UUID finalUuid = uuid;
      final String mcName = entry.minecraftUsername();

      this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
         try {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(finalUuid);
            offlinePlayer.setWhitelisted(false);

            if (this.plugin.getConfig().getBoolean("settings.kick-on-unwhitelist", true)) {
               Player onlinePlayer = Bukkit.getPlayer(finalUuid);
               if (onlinePlayer != null && onlinePlayer.isOnline()) {
                  String kickRaw = this.plugin.getConfig().getString("settings.kick-message", "&cYou have been removed from the whitelist.");
                  Component kickComp = LegacyComponentSerializer.legacyAmpersand().deserialize(kickRaw);
                  onlinePlayer.kick(kickComp);
               }
            }

            this.updateWhitelistJsonRemove(finalUuid);
            Bukkit.reloadWhitelist();
         } catch (Exception e) {
            this.plugin.getLogger().warning("Failed to update whitelist.json on unwhitelist: " + e.getMessage());
         }
      });

      this.discordUsers.remove(entry.discordId());
      this.saveDiscordUsers();

      String msg = this.msg("unwhitelist-success", "Successfully unwhitelisted %username%.")
         .replace("%username%", mcName)
         .replace("%discord_id%", entry.discordId());

      return CompletableFuture.completedFuture(new Result(true, msg, entry, null));
   }

   public DiscordEntry lookup(String target) {
      if (target == null || target.isBlank()) {
         return null;
      }
      String clean = target.replaceAll("[<@!>]", "").trim();

      DiscordEntry entry = this.discordUsers.get(clean);
      if (entry != null) {
         return entry;
      }

      entry = this.getEntryByMinecraftName(clean);
      if (entry != null) {
         return entry;
      }

      entry = this.getEntryByUuid(clean);
      if (entry != null) {
         return entry;
      }

      for (DiscordEntry e : this.discordUsers.values()) {
         if (e.discordName().equalsIgnoreCase(clean)) {
            return e;
         }
      }

      return null;
   }

   public List<DiscordEntry> searchEntries(String query) {
      if (query == null || query.isBlank()) {
         return new ArrayList<>(this.discordUsers.values());
      }
      String q = query.toLowerCase().trim();
      List<DiscordEntry> matched = new ArrayList<>();
      for (DiscordEntry e : this.discordUsers.values()) {
         if (e.minecraftUsername().toLowerCase().contains(q)
            || e.discordName().toLowerCase().contains(q)
            || e.discordId().contains(q)) {
            matched.add(e);
         }
      }
      return matched;
   }

   public List<DiscordEntry> getPagedList(List<DiscordEntry> list, int page, int pageSize) {
      int start = (page - 1) * pageSize;
      if (start >= list.size() || start < 0) {
         return Collections.emptyList();
      }
      int end = Math.min(start + pageSize, list.size());
      return list.subList(start, end);
   }

   public DiscordEntry getEntryByDiscordId(String discordId) {
      return this.discordUsers.get(discordId);
   }

   public DiscordEntry getEntryByMinecraftName(String username) {
      if (username == null) return null;
      for (DiscordEntry entry : this.discordUsers.values()) {
         if (entry.minecraftUsername().equalsIgnoreCase(username)) {
            return entry;
         }
      }
      return null;
   }

   public DiscordEntry getEntryByUuid(String uuid) {
      if (uuid == null) return null;
      for (DiscordEntry entry : this.discordUsers.values()) {
         if (entry.uuid().equalsIgnoreCase(uuid)) {
            return entry;
         }
      }
      return null;
   }

   public Map<String, DiscordEntry> getAllEntries() {
      return Collections.unmodifiableMap(this.discordUsers);
   }

   public int getWhitelistedCount() {
      return this.discordUsers.size();
   }

   public List<DiscordEntry> getPagedEntries(int page, int pageSize) {
      List<DiscordEntry> list = new ArrayList<>(this.discordUsers.values());
      int start = (page - 1) * pageSize;
      if (start >= list.size() || start < 0) {
         return Collections.emptyList();
      }
      int end = Math.min(start + pageSize, list.size());
      return list.subList(start, end);
   }

   public String msg(String key, String fallback) {
      return this.plugin.getConfig().getString("messages." + key, fallback);
   }

   private synchronized void updateWhitelistJsonAdd(UUID uuid, String username) throws IOException {
      JsonArray whitelist = this.readWhitelist();
      boolean found = false;
      for (JsonElement el : whitelist) {
         if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            String existingUuid = obj.has("uuid") ? obj.get("uuid").getAsString() : "";
            if (existingUuid.equalsIgnoreCase(uuid.toString())) {
               obj.addProperty("name", username);
               found = true;
               break;
            }
         }
      }
      if (!found) {
         JsonObject obj = new JsonObject();
         obj.addProperty("uuid", uuid.toString());
         obj.addProperty("name", username);
         whitelist.add(obj);
      }
      this.writeWhitelist(whitelist);
   }

   private synchronized void updateWhitelistJsonRemove(UUID uuid) throws IOException {
      JsonArray whitelist = this.readWhitelist();
      JsonArray updated = new JsonArray();
      for (JsonElement el : whitelist) {
         if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            String existingUuid = obj.has("uuid") ? obj.get("uuid").getAsString() : "";
            if (!existingUuid.equalsIgnoreCase(uuid.toString())) {
               updated.add(el);
            }
         }
      }
      this.writeWhitelist(updated);
   }

   private JsonArray readWhitelist() throws IOException {
      if (!this.whitelistJsonFile.exists()) {
         return new JsonArray();
      }
      try (FileReader reader = new FileReader(this.whitelistJsonFile, StandardCharsets.UTF_8)) {
         JsonElement parsed = JsonParser.parseReader(reader);
         if (parsed == null || !parsed.isJsonArray()) {
            return new JsonArray();
         }
         return parsed.getAsJsonArray();
      }
   }

   private void writeWhitelist(JsonArray whitelist) throws IOException {
      try (FileWriter writer = new FileWriter(this.whitelistJsonFile, StandardCharsets.UTF_8)) {
         this.gson.toJson(whitelist, writer);
      }
   }

   private void loadDiscordUsers() {
      if (!this.discordDataFile.exists()) {
         return;
      }
      try (FileReader reader = new FileReader(this.discordDataFile, StandardCharsets.UTF_8)) {
         Type type = new TypeToken<LinkedHashMap<String, DiscordEntry>>() {}.getType();
         Map<String, DiscordEntry> loaded = this.gson.fromJson(reader, type);
         if (loaded != null) {
            this.discordUsers.putAll(loaded);
         }
      } catch (IOException e) {
         this.plugin.getLogger().warning("Could not read discord_whitelist_data.json: " + e.getMessage());
      }
   }

   public synchronized void saveDiscordUsers() {
      try (FileWriter writer = new FileWriter(this.discordDataFile, StandardCharsets.UTF_8)) {
         this.gson.toJson(this.discordUsers, writer);
      } catch (IOException e) {
         this.plugin.getLogger().severe("Failed to save discord_whitelist_data.json: " + e.getMessage());
      }
   }

   public static record DiscordEntry(String discordId, String discordName, String minecraftUsername, String uuid, long timestamp) {}

   public static record Result(boolean success, String message, DiscordEntry entry, UuidService.ProfileResult profile) {
      public Result(boolean success, String message) {
         this(success, message, null, null);
      }
   }
}

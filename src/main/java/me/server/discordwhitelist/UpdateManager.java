package me.server.discordwhitelist;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

public class UpdateManager implements Listener {
   private final DiscordWhitelistPlugin plugin;
   private final HttpClient httpClient;
   private final String currentVersion;

   private String latestVersion = null;
   private String downloadUrl = null;
   private String releaseHtmlUrl = null;
   private String releaseNotes = null;
   private boolean updateAvailable = false;
   private BukkitTask periodicTask = null;

   public UpdateManager(DiscordWhitelistPlugin plugin) {
      this.plugin = plugin;
      this.currentVersion = plugin.getDescription().getVersion();
      this.httpClient = HttpClient.newBuilder()
         .connectTimeout(Duration.ofSeconds(6))
         .followRedirects(HttpClient.Redirect.NORMAL)
         .build();
   }

   public void start() {
      if (!this.plugin.getConfig().getBoolean("updater.enabled", true)) {
         return;
      }

      // Check on startup asynchronously after 5 seconds to let bot and server stabilize
      Bukkit.getScheduler().runTaskLaterAsynchronously(this.plugin, () -> this.checkForUpdates(false, null), 100L);

      int intervalHours = this.plugin.getConfig().getInt("updater.check-interval-hours", 12);
      if (intervalHours > 0) {
         long ticks = intervalHours * 60L * 60L * 20L;
         this.periodicTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            this.plugin,
            () -> this.checkForUpdates(false, null),
            ticks,
            ticks
         );
      }
   }

   public void cancelTask() {
      if (this.periodicTask != null) {
         this.periodicTask.cancel();
         this.periodicTask = null;
      }
   }

   public CompletableFuture<UpdateCheckResult> checkForUpdates(boolean manual, CommandSender sender) {
      String repo = this.plugin.getConfig().getString("updater.github-repo", "Wuolp/DiscordWhitelist");
      if (repo == null || repo.isBlank() || repo.equalsIgnoreCase("PUT_GITHUB_REPO_HERE")) {
         if (sender != null) {
            sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&c[DiscordWhitelist] No GitHub repository configured in config.yml (updater.github-repo)."));
         }
         return CompletableFuture.completedFuture(new UpdateCheckResult(false, this.currentVersion, null, null, null, "No repository configured"));
      }

      String url = "https://api.github.com/repos/" + repo.trim() + "/releases/latest";
      HttpRequest req = HttpRequest.newBuilder()
         .uri(URI.create(url))
         .timeout(Duration.ofSeconds(8))
         .header("User-Agent", "DiscordWhitelist-UpdateChecker")
         .header("Accept", "application/vnd.github.v3+json")
         .GET()
         .build();

      return this.httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
         int code = res.statusCode();
         if (code == 200) {
            try {
               JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
               String tag = json.has("tag_name") ? json.get("tag_name").getAsString() : "";
               String cleanLatest = tag.replaceAll("^[vV]", "").trim();
               String htmlUrl = json.has("html_url") ? json.get("html_url").getAsString() : "https://github.com/" + repo;
               String body = json.has("body") ? json.get("body").getAsString() : "";

               String jarDownload = htmlUrl;
               if (json.has("assets") && json.get("assets").isJsonArray()) {
                  JsonArray assets = json.getAsJsonArray("assets");
                  for (JsonElement el : assets) {
                     if (el.isJsonObject()) {
                        JsonObject assetObj = el.getAsJsonObject();
                        String name = assetObj.has("name") ? assetObj.get("name").getAsString() : "";
                        if (name.endsWith(".jar")) {
                           jarDownload = assetObj.has("browser_download_url")
                              ? assetObj.get("browser_download_url").getAsString()
                              : htmlUrl;
                           break;
                        }
                     }
                  }
               }

               this.latestVersion = cleanLatest;
               this.releaseHtmlUrl = htmlUrl;
               this.downloadUrl = jarDownload;
               this.releaseNotes = body;
               this.updateAvailable = isNewerVersion(this.currentVersion, cleanLatest);

               if (this.updateAvailable) {
                  this.plugin.getLogger().info("=================================================");
                  this.plugin.getLogger().info("A new update for DiscordWhitelist is available!");
                  this.plugin.getLogger().info("Current version: v" + this.currentVersion + " | Latest: v" + cleanLatest);
                  this.plugin.getLogger().info("Download URL: " + this.downloadUrl);
                  this.plugin.getLogger().info("=================================================");

                  if (this.plugin.getConfig().getBoolean("updater.notify-discord", true)) {
                     this.notifyDiscordUpdate();
                  }

                  if (this.plugin.getConfig().getBoolean("updater.auto-download", false)) {
                     this.downloadUpdate(null);
                  }
               } else if (manual && sender != null) {
                  sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&a[DiscordWhitelist] You are running the latest version (v" + this.currentVersion + ")."));
               }

               return new UpdateCheckResult(this.updateAvailable, this.currentVersion, cleanLatest, this.downloadUrl, body, null);
            } catch (Exception e) {
               this.plugin.getLogger().warning("Failed to parse update information: " + e.getMessage());
               return new UpdateCheckResult(false, this.currentVersion, null, null, null, e.getMessage());
            }
         } else if (code == 404) {
            if (manual && sender != null) {
               sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&e[DiscordWhitelist] No GitHub releases found for '" + repo + "'."));
            }
            return new UpdateCheckResult(false, this.currentVersion, null, null, null, "Repository or release not found (HTTP 404)");
         } else {
            return new UpdateCheckResult(false, this.currentVersion, null, null, null, "GitHub API returned status HTTP " + code);
         }
      }).exceptionally(ex -> {
         this.plugin.getLogger().warning("Error checking for updates: " + ex.getMessage());
         if (sender != null) {
            sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&c[DiscordWhitelist] Failed to check for updates: " + ex.getMessage()));
         }
         return new UpdateCheckResult(false, this.currentVersion, null, null, null, ex.getMessage());
      });
   }

   public CompletableFuture<DownloadResult> downloadUpdate(CommandSender sender) {
      if (!this.updateAvailable || this.downloadUrl == null) {
         if (sender != null) {
            sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&e[DiscordWhitelist] No update available to download. Run &f/dwl update check &efirst."));
         }
         return CompletableFuture.completedFuture(new DownloadResult(false, "No update available to download."));
      }

      if (sender != null) {
         sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&e[DiscordWhitelist] Downloading update v" + this.latestVersion + "..."));
      }

      File updateFolder = new File(this.plugin.getDataFolder().getParentFile(), this.plugin.getServer().getUpdateFolder());
      if (!updateFolder.exists()) {
         updateFolder.mkdirs();
      }

      String fileName = this.plugin.getPluginFile().getName();
      File targetFile = new File(updateFolder, fileName);

      HttpRequest req = HttpRequest.newBuilder()
         .uri(URI.create(this.downloadUrl))
         .timeout(Duration.ofSeconds(30))
         .header("User-Agent", "DiscordWhitelist-Downloader")
         .GET()
         .build();

      return this.httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream()).thenApply(res -> {
         if (res.statusCode() == 200) {
            try (InputStream in = res.body(); FileOutputStream out = new FileOutputStream(targetFile)) {
               byte[] buffer = new byte[8192];
               int bytesRead;
               while ((bytesRead = in.read(buffer)) != -1) {
                  out.write(buffer, 0, bytesRead);
               }
               String successMsg = "&a[DiscordWhitelist] Successfully downloaded v" + this.latestVersion + " to "
                  + targetFile.getName() + "! It will automatically apply on the next server restart.";
               this.plugin.getLogger().info("Successfully downloaded update v" + this.latestVersion + " to " + targetFile.getAbsolutePath());
               if (sender != null) {
                  sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(successMsg));
               }
               return new DownloadResult(true, "Downloaded to " + targetFile.getName());
            } catch (Exception e) {
               String err = "Failed to write downloaded update file: " + e.getMessage();
               this.plugin.getLogger().warning(err);
               if (sender != null) {
                  sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&c[DiscordWhitelist] " + err));
               }
               return new DownloadResult(false, err);
            }
         } else {
            String err = "Failed to download update: HTTP " + res.statusCode();
            this.plugin.getLogger().warning(err);
            if (sender != null) {
               sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&c[DiscordWhitelist] " + err));
            }
            return new DownloadResult(false, err);
         }
      }).exceptionally(ex -> {
         String err = "Download failed: " + ex.getMessage();
         this.plugin.getLogger().warning(err);
         if (sender != null) {
            sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&c[DiscordWhitelist] " + err));
         }
         return new DownloadResult(false, err);
      });
   }

   @EventHandler
   public void onPlayerJoin(PlayerJoinEvent event) {
      if (!this.updateAvailable) return;
      if (!this.plugin.getConfig().getBoolean("updater.notify-in-game", true)) return;

      if (event.getPlayer().hasPermission("discordwhitelist.admin")) {
         Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (event.getPlayer().isOnline()) {
               event.getPlayer().sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                  "&6[DiscordWhitelist] &eA new update is available: &a&lv" + this.latestVersion
                     + " &7(Current: v" + this.currentVersion + ")"
               ));
               event.getPlayer().sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                  "&eDownload Link: &b" + (this.releaseHtmlUrl != null ? this.releaseHtmlUrl : this.downloadUrl)
               ));
               event.getPlayer().sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                  "&7Type &f/dwl update download &7to automatically download the new version for next restart."
               ));
            }
         }, 40L);
      }
   }

   private void notifyDiscordUpdate() {
      if (this.plugin.getBotHandler() == null) return;
      String desc = "A new version of **DiscordWhitelist** is available!\n\n"
         + "• **Current:** `v" + this.currentVersion + "`\n"
         + "• **Latest:** `v" + this.latestVersion + "`\n"
         + "• **Download:** [Click here to view release](" + this.releaseHtmlUrl + ")";

      this.plugin.getBotHandler().logWhitelistEvent(
         "🚀 Plugin Update Available (v" + this.latestVersion + ")",
         desc,
         Color.decode("#F39C12"),
         null
      );
   }

   public static boolean isNewerVersion(String current, String latest) {
      if (current == null || latest == null) return false;
      String cleanCur = current.trim().replaceAll("^[vV]", "").split("-")[0];
      String cleanLat = latest.trim().replaceAll("^[vV]", "").split("-")[0];

      String[] curParts = cleanCur.split("\\.");
      String[] latParts = cleanLat.split("\\.");
      int length = Math.max(curParts.length, latParts.length);

      for (int i = 0; i < length; i++) {
         int curNum = i < curParts.length ? parseNumber(curParts[i]) : 0;
         int latNum = i < latParts.length ? parseNumber(latParts[i]) : 0;
         if (latNum > curNum) {
            return true;
         } else if (latNum < curNum) {
            return false;
         }
      }
      return false;
   }

   private static int parseNumber(String str) {
      try {
         return Integer.parseInt(str.replaceAll("[^0-9]", ""));
      } catch (NumberFormatException e) {
         return 0;
      }
   }

   public String getCurrentVersion() {
      return this.currentVersion;
   }

   public String getLatestVersion() {
      return this.latestVersion;
   }

   public String getDownloadUrl() {
      return this.downloadUrl;
   }

   public String getReleaseHtmlUrl() {
      return this.releaseHtmlUrl;
   }

   public String getReleaseNotes() {
      return this.releaseNotes;
   }

   public boolean isUpdateAvailable() {
      return this.updateAvailable;
   }

   public static record UpdateCheckResult(boolean hasUpdate, String currentVersion, String latestVersion, String downloadUrl, String notes, String error) {}

   public static record DownloadResult(boolean success, String message) {}
}

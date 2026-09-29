package me.server.discordwhitelist;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class DiscordWhitelistPlugin extends JavaPlugin {
   private JDA jda;
   private WhitelistManager whitelistManager;
   private DiscordBotHandler botHandler;
   private UpdateManager updateManager;

   @Override
   public void onEnable() {
      this.saveDefaultConfig();
      String token = this.getConfig().getString("bot-token", "");

      if (token == null || token.isBlank() || token.equals("PUT_YOUR_BOT_TOKEN_HERE")) {
         this.getLogger().severe("No valid Discord bot token set in plugins/DiscordWhitelist/config.yml — plugin disabled.");
         this.getServer().getPluginManager().disablePlugin(this);
         return;
      }

      this.whitelistManager = new WhitelistManager(this);
      this.botHandler = new DiscordBotHandler(this, this.whitelistManager);

      boolean syncGuildLeave = this.getConfig().getBoolean("settings.remove-whitelist-on-guild-leave", false);

      try {
         JDABuilder builder = JDABuilder.createDefault(token)
            .addEventListeners(this.botHandler)
            .setActivity(this.parseActivity())
            .setStatus(this.parseStatus());

         if (syncGuildLeave) {
            builder.enableIntents(GatewayIntent.GUILD_MEMBERS);
         }

         this.jda = builder.build();
      } catch (Exception e) {
         if (syncGuildLeave) {
            this.getLogger().warning("Failed to start with GUILD_MEMBERS intent (make sure it's enabled in Discord Developer Portal). Retrying with default intents...");
            try {
               this.jda = JDABuilder.createDefault(token)
                  .addEventListeners(this.botHandler)
                  .setActivity(this.parseActivity())
                  .setStatus(this.parseStatus())
                  .build();
            } catch (Exception fallbackEx) {
               this.getLogger().severe("Failed to start Discord bot: " + fallbackEx.getMessage());
               this.getServer().getPluginManager().disablePlugin(this);
               return;
            }
         } else {
            this.getLogger().severe("Failed to start Discord bot: " + e.getMessage());
            e.printStackTrace();
            this.getServer().getPluginManager().disablePlugin(this);
            return;
         }
      }

      DiscordWhitelistCommand cmdExecutor = new DiscordWhitelistCommand(this, this.whitelistManager, this.botHandler);

      PluginCommand mainCmd = this.getCommand("discordwhitelist");
      if (mainCmd != null) {
         mainCmd.setExecutor(cmdExecutor);
         mainCmd.setTabCompleter(cmdExecutor);
      }

      PluginCommand panelCmd = this.getCommand("discordwhitelistpanel");
      if (panelCmd != null) {
         panelCmd.setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("discordwhitelist.admin")) {
               sender.sendMessage("§cYou don't have permission to use this command.");
               return true;
            }
            this.botHandler.postPanel(null);
            sender.sendMessage("§a[DiscordWhitelist] Whitelist panel posted to the configured channel.");
            return true;
         });
      }

      this.updateManager = new UpdateManager(this);
      this.getServer().getPluginManager().registerEvents(this.updateManager, this);
      this.updateManager.start();

      this.getLogger().info("DiscordWhitelist has started successfully!");
   }

   @Override
   public void onDisable() {
      if (this.updateManager != null) {
         this.updateManager.cancelTask();
      }
      if (this.whitelistManager != null) {
         this.whitelistManager.saveDiscordUsers();
      }
      if (this.jda != null) {
         this.jda.shutdown();
      }
      this.getLogger().info("DiscordWhitelist disabled.");
   }

   public void reloadPluginConfig() {
      this.reloadConfig();
      if (this.jda != null) {
         try {
            this.jda.getPresence().setPresence(this.parseStatus(), this.parseActivity());
         } catch (Exception ignored) {}
      }
      this.getLogger().info("Plugin configuration reloaded.");
   }

   public Activity parseActivity() {
      String type = this.getConfig().getString("presence.activity-type", "WATCHING").toUpperCase();
      String text = this.getConfig().getString("presence.activity-text", "for /whitelist requests");

      return switch (type) {
         case "PLAYING" -> Activity.playing(text);
         case "LISTENING" -> Activity.listening(text);
         case "COMPETING" -> Activity.competing(text);
         default -> Activity.watching(text);
      };
   }

   public OnlineStatus parseStatus() {
      String status = this.getConfig().getString("presence.status", "ONLINE").toUpperCase();
      return switch (status) {
         case "IDLE" -> OnlineStatus.IDLE;
         case "DND", "DO_NOT_DISTURB" -> OnlineStatus.DO_NOT_DISTURB;
         case "INVISIBLE" -> OnlineStatus.INVISIBLE;
         default -> OnlineStatus.ONLINE;
      };
   }

   public JDA getJda() {
      return this.jda;
   }

   public WhitelistManager getWhitelistManager() {
      return this.whitelistManager;
   }

   public DiscordBotHandler getBotHandler() {
      return this.botHandler;
   }

   public UpdateManager getUpdateManager() {
      return this.updateManager;
   }

   public java.io.File getPluginFile() {
      return this.getFile();
   }
}

package me.server.discordwhitelist;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class DiscordWhitelistCommand implements CommandExecutor, TabCompleter {
   private final DiscordWhitelistPlugin plugin;
   private final WhitelistManager whitelistManager;
   private final DiscordBotHandler botHandler;

   public DiscordWhitelistCommand(DiscordWhitelistPlugin plugin, WhitelistManager whitelistManager, DiscordBotHandler botHandler) {
      this.plugin = plugin;
      this.whitelistManager = whitelistManager;
      this.botHandler = botHandler;
   }

   private void msg(CommandSender sender, String text) {
      sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(text));
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!sender.hasPermission("discordwhitelist.admin")) {
         this.msg(sender, "&cYou don't have permission to use this command.");
         return true;
      }

      if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
         this.sendHelp(sender, label);
         return true;
      }

      String sub = args[0].toLowerCase();

      switch (sub) {
         case "reload" -> {
            this.plugin.reloadPluginConfig();
            this.msg(sender, "&a[DiscordWhitelist] Configuration and bot presence reloaded!");
            return true;
         }
         case "panel" -> {
            TextChannel channel = null;
            if (args.length > 1) {
               String chId = args[1];
               if (this.plugin.getJda() != null) {
                  channel = this.plugin.getJda().getTextChannelById(chId);
               }
               if (channel == null) {
                  this.msg(sender, "&c[DiscordWhitelist] Could not find text channel with ID: " + chId);
                  return true;
               }
            }
            this.botHandler.postPanel(channel);
            this.msg(sender, "&a[DiscordWhitelist] Whitelist panel posted!");
            return true;
         }
         case "info", "whois" -> {
            if (args.length < 2) {
               this.msg(sender, "&cUsage: /" + label + " whois <username|discordId>");
               return true;
            }
            String target = args[1];
            WhitelistManager.DiscordEntry entry = this.whitelistManager.lookup(target);
            if (entry == null) {
               this.msg(sender, "&c[DiscordWhitelist] No whitelist record found for '" + target + "'.");
               return true;
            }
            this.msg(sender, "&6=== [Discord Whois: " + entry.minecraftUsername() + "] ===");
            this.msg(sender, "&eMinecraft Name: &f" + entry.minecraftUsername());
            this.msg(sender, "&eUUID: &f" + entry.uuid());
            this.msg(sender, "&eDiscord Tag: &b@" + entry.discordName());
            this.msg(sender, "&eDiscord ID: &f" + entry.discordId());
            this.msg(sender, "&eLinked Date: &f" + new Date(entry.timestamp()));
            return true;
         }
         case "online" -> {
            Collection<? extends Player> online = Bukkit.getOnlinePlayers();
            this.msg(sender, "&6=== [Online Players & Linked Discord (" + online.size() + "/" + Bukkit.getMaxPlayers() + ")] ===");
            if (online.isEmpty()) {
               this.msg(sender, "&7No players are currently online.");
            } else {
               for (Player p : online) {
                  WhitelistManager.DiscordEntry entry = this.whitelistManager.getEntryByMinecraftName(p.getName());
                  if (entry == null) {
                     entry = this.whitelistManager.getEntryByUuid(p.getUniqueId().toString());
                  }
                  if (entry != null) {
                     this.msg(sender, "&a• &f" + p.getName() + " &7➔ &b@" + entry.discordName() + " &7(ID: " + entry.discordId() + ")");
                  } else {
                     this.msg(sender, "&e• &f" + p.getName() + " &7➔ &c(Not linked to Discord)");
                  }
               }
            }
            return true;
         }
         case "unlink", "remove" -> {
            if (args.length < 2) {
               this.msg(sender, "&cUsage: /" + label + " unlink <username|discordId>");
               return true;
            }
            String target = args[1];
            this.msg(sender, "&e[DiscordWhitelist] Processing unwhitelist for '" + target + "'...");
            this.whitelistManager.unwhitelistAsync(target, sender.getName()).thenAccept(result -> {
               if (result.success()) {
                  this.msg(sender, "&a[DiscordWhitelist] " + result.message());
               } else {
                  this.msg(sender, "&c[DiscordWhitelist] " + result.message());
               }
            });
            return true;
         }
         case "add", "link" -> {
            if (args.length < 3) {
               this.msg(sender, "&cUsage: /" + label + " add <discordId> <minecraftUsername>");
               return true;
            }
            String discordId = args[1];
            String mcUsername = args[2];
            this.msg(sender, "&e[DiscordWhitelist] Processing whitelist for '" + mcUsername + "'...");
            this.whitelistManager.whitelistPlayerAsync(discordId, "ManualAdminLink", mcUsername, true).thenAccept(result -> {
               if (result.success()) {
                  this.msg(sender, "&a[DiscordWhitelist] Successfully linked and whitelisted " + result.profile().exactName() + "!");
               } else {
                  this.msg(sender, "&c[DiscordWhitelist] Failed: " + result.message());
               }
            });
            return true;
         }
         case "list" -> {
            int total = this.whitelistManager.getWhitelistedCount();
            int page = 1;
            if (args.length > 1) {
               try {
                  page = Math.max(1, Integer.parseInt(args[1]));
               } catch (NumberFormatException ignored) {}
            }
            int pageSize = 8;
            int totalPages = Math.max(1, (int) Math.ceil((double) total / pageSize));
            page = Math.min(page, totalPages);

            List<WhitelistManager.DiscordEntry> entries = this.whitelistManager.getPagedEntries(page, pageSize);
            this.msg(sender, "&6=== [Whitelisted Accounts (" + total + " total - Page " + page + "/" + totalPages + ")] ===");
            if (entries.isEmpty()) {
               this.msg(sender, "&7No whitelisted accounts found.");
            } else {
               int idx = (page - 1) * pageSize + 1;
               for (WhitelistManager.DiscordEntry e : entries) {
                  this.msg(sender, "&e" + idx++ + ". &f" + e.minecraftUsername() + " &7➔ &b@" + e.discordName() + " &7(" + e.discordId() + ")");
               }
            }
            return true;
         }
         case "update" -> {
            String action = args.length > 1 ? args[1].toLowerCase() : "check";
            if ("download".equals(action)) {
               this.plugin.getUpdateManager().downloadUpdate(sender);
            } else {
               this.msg(sender, "&e[DiscordWhitelist] Checking for updates...");
               this.plugin.getUpdateManager().checkForUpdates(true, sender);
            }
            return true;
         }
         default -> {
            this.sendHelp(sender, label);
            return true;
         }
      }
   }

   private void sendHelp(CommandSender sender, String label) {
      this.msg(sender, "&6=== [DiscordWhitelist Commands] ===");
      this.msg(sender, "&e/" + label + " whois <player|discordId> &7- Look up player's Discord account");
      this.msg(sender, "&e/" + label + " online &7- View online players & linked Discord tags");
      this.msg(sender, "&e/" + label + " list [page] &7- List all whitelisted/registered accounts");
      this.msg(sender, "&e/" + label + " update [check|download] &7- Check for or install updates");
      this.msg(sender, "&e/" + label + " panel [channelId] &7- Post the Discord whitelist panel");
      this.msg(sender, "&e/" + label + " reload &7- Reload config and bot status");
      this.msg(sender, "&e/" + label + " unlink <username|discordId> &7- Remove a player from whitelist");
      this.msg(sender, "&e/" + label + " add <discordId> <username> &7- Force link a player");
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (!sender.hasPermission("discordwhitelist.admin")) {
         return Collections.emptyList();
      }

      if (args.length == 1) {
         List<String> subs = Arrays.asList("whois", "online", "list", "update", "panel", "reload", "unlink", "add", "help");
         return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
      }

      if (args.length == 2) {
         String sub = args[0].toLowerCase();
         if ("update".equals(sub)) {
            List<String> options = Arrays.asList("check", "download");
            return options.stream().filter(s -> s.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
         }
         if ("info".equals(sub) || "whois".equals(sub) || "unlink".equals(sub)) {
            List<String> suggestions = new ArrayList<>();
            for (WhitelistManager.DiscordEntry e : this.whitelistManager.getAllEntries().values()) {
               suggestions.add(e.minecraftUsername());
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
               if (!suggestions.contains(p.getName())) {
                  suggestions.add(p.getName());
               }
            }
            return suggestions.stream().filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
         }
      }

      return Collections.emptyList();
   }
}

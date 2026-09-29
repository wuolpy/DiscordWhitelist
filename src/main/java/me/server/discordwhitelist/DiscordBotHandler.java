package me.server.discordwhitelist;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public class DiscordBotHandler extends ListenerAdapter {
   public static final String BTN_OPEN_MODAL = "discordwhitelist:open_modal";
   public static final String BTN_CHECK_STATUS = "discordwhitelist:check_status";
   public static final String BTN_UNLINK_SELF = "discordwhitelist:unlink_self";
   public static final String BTN_VIEW_REGISTERED = "discordwhitelist:view_registered";
   public static final String MODAL_SUBMIT = "discordwhitelist:submit_modal";
   public static final String INPUT_USERNAME = "mc_username";

   private final DiscordWhitelistPlugin plugin;
   private final WhitelistManager whitelistManager;

   public DiscordBotHandler(DiscordWhitelistPlugin plugin, WhitelistManager whitelistManager) {
      this.plugin = plugin;
      this.whitelistManager = whitelistManager;
   }

   @Override
   public void onReady(ReadyEvent event) {
      this.plugin.getLogger().info("Discord bot connected as " + event.getJDA().getSelfUser().getAsTag());
      this.registerSlashCommands(event);

      if (this.plugin.getConfig().getBoolean("auto-post-panel-on-startup", false)) {
         this.postPanel(null);
      }
   }

   private void registerSlashCommands(ReadyEvent event) {
      List<CommandData> commandList = new ArrayList<>();

      SlashCommandData whitelistCmd = Commands.slash("whitelist", "Manage and request server whitelist")
         .addSubcommands(
            new SubcommandData("add", "Whitelist a Minecraft username")
               .addOption(OptionType.STRING, "username", "Your Minecraft username", true)
               .addOption(OptionType.USER, "user", "(Staff only) Discord user to whitelist for", false),
            new SubcommandData("remove", "Remove an account from the whitelist")
               .addOption(OptionType.STRING, "target", "Minecraft username or Discord ID (leave blank to unwhitelist yourself)", false),
            new SubcommandData("info", "View whitelist details for yourself or another user")
               .addOption(OptionType.STRING, "target", "Minecraft username, Discord @mention, or user ID", false, true),
            new SubcommandData("list", "View whitelisted/registered players")
               .addOption(OptionType.INTEGER, "page", "Page number (default: 1)", false)
               .addOption(OptionType.STRING, "search", "Search by Minecraft name or Discord handle", false),
            new SubcommandData("panel", "(Staff only) Send the interactive whitelist panel")
               .addOption(OptionType.CHANNEL, "channel", "Text channel to send the panel to", false),
            new SubcommandData("reload", "(Staff only) Reload the plugin configuration"),
            new SubcommandData("update", "(Staff only) Check for or download plugin updates")
               .addOption(OptionType.STRING, "action", "Action: check or download (default: check)", false)
         );

      SlashCommandData unwhitelistCmd = Commands.slash("unwhitelist", "Unwhitelist your account or a player (Staff)")
         .addOption(OptionType.STRING, "target", "Minecraft username or Discord user (leave blank to unwhitelist yourself)", false);

      SlashCommandData whoisCmd = Commands.slash("whois", "Look up a player's Discord account or Minecraft link")
         .addOption(OptionType.STRING, "target", "Minecraft username, Discord @mention, or user ID", true, true);

      SlashCommandData registeredCmd = Commands.slash("registered", "View the directory of whitelisted/registered players")
         .addOption(OptionType.INTEGER, "page", "Page number (default: 1)", false)
         .addOption(OptionType.STRING, "search", "Search by Minecraft or Discord name", false);

      SlashCommandData onlineCmd = Commands.slash("online", "View players currently online in Minecraft and their Discord accounts");

      SlashCommandData statusCmd = Commands.slash("server-status", "Check Minecraft server status, players online, and ping");

      commandList.add(whitelistCmd);
      commandList.add(unwhitelistCmd);
      commandList.add(whoisCmd);
      commandList.add(registeredCmd);
      commandList.add(onlineCmd);
      commandList.add(statusCmd);

      // Register immediately to specific guild if guild-id is configured
      String guildId = this.plugin.getConfig().getString("guild-id", "");
      if (guildId != null && !guildId.isBlank()) {
         Guild guild = event.getJDA().getGuildById(guildId);
         if (guild != null) {
            guild.updateCommands().addCommands(commandList).queue(
               ok -> this.plugin.getLogger().info("Registered " + commandList.size() + " slash commands to guild: " + guild.getName()),
               err -> this.plugin.getLogger().warning("Failed to register guild commands: " + err.getMessage())
            );
         } else {
            this.plugin.getLogger().warning("Configured guild-id '" + guildId + "' was not found. Commands will update globally.");
         }
      }

      // Also register globally (takes ~1 hour to cache across all guilds)
      event.getJDA().updateCommands().addCommands(commandList).queue(
         ok -> this.plugin.getLogger().info("Registered global Discord slash commands."),
         err -> this.plugin.getLogger().warning("Failed to register global commands: " + err.getMessage())
      );
   }

   @Override
   public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
      String cmd = event.getName();
      String sub = event.getSubcommandName();

      boolean isWhoisTarget = ("whois".equals(cmd) || ("whitelist".equals(cmd) && "info".equals(sub)))
         && "target".equals(event.getFocusedOption().getName());

      if (isWhoisTarget) {
         String query = event.getFocusedOption().getValue().toLowerCase().trim();
         List<Command.Choice> choices = this.whitelistManager.getAllEntries().values().stream()
            .filter(e -> e.minecraftUsername().toLowerCase().contains(query)
               || e.discordName().toLowerCase().contains(query)
               || e.discordId().startsWith(query))
            .limit(25)
            .map(e -> new Command.Choice(e.minecraftUsername() + " (Discord: @" + e.discordName() + ")", e.minecraftUsername()))
            .collect(Collectors.toList());
         event.replyChoices(choices).queue();
      }
   }

   @Override
   public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
      String name = event.getName();

      switch (name) {
         case "whitelist" -> {
            String sub = event.getSubcommandName();
            if (sub == null) return;
            switch (sub) {
               case "add" -> this.handleWhitelistAdd(event);
               case "remove" -> this.handleWhitelistRemove(event);
               case "info" -> this.handleWhois(event);
               case "list" -> this.handleRegistered(event);
               case "panel" -> this.handleWhitelistPanel(event);
               case "reload" -> this.handleWhitelistReload(event);
               case "update" -> this.handleWhitelistUpdate(event);
               default -> event.reply("Unknown subcommand.").setEphemeral(true).queue();
            }
         }
         case "unwhitelist" -> this.handleWhitelistRemove(event);
         case "whois" -> this.handleWhois(event);
         case "registered" -> this.handleRegistered(event);
         case "online" -> this.handleOnline(event);
         case "server-status" -> this.handleServerStatus(event);
      }
   }

   private void handleWhitelistAdd(SlashCommandInteractionEvent event) {
      OptionMapping usernameOpt = event.getOption("username");
      if (usernameOpt == null) {
         event.reply("Please specify a Minecraft username.").setEphemeral(true).queue();
         return;
      }
      String username = usernameOpt.getAsString().trim();

      OptionMapping userOpt = event.getOption("user");
      boolean isAdminWhitelistingOther = userOpt != null && !userOpt.getAsUser().getId().equals(event.getUser().getId());

      if (isAdminWhitelistingOther && !this.isAdmin(event.getMember())) {
         event.reply(this.whitelistManager.msg("no-permission", "You do not have permission to whitelist other users.")).setEphemeral(true).queue();
         return;
      }

      if (!isAdminWhitelistingOther && !this.hasRequiredRole(event.getMember())) {
         String reqRoleId = this.plugin.getConfig().getString("roles.required-role-id", "");
         String msg = this.whitelistManager.msg("missing-required-role", "You must have the required role to whitelist.")
            .replace("%role_id%", reqRoleId);
         event.reply(msg).setEphemeral(true).queue();
         return;
      }

      String targetDiscordId = isAdminWhitelistingOther ? userOpt.getAsUser().getId() : event.getUser().getId();
      String targetDiscordName = isAdminWhitelistingOther ? userOpt.getAsUser().getName() : event.getUser().getName();

      event.deferReply(true).queue();

      this.whitelistManager.whitelistPlayerAsync(targetDiscordId, targetDiscordName, username, isAdminWhitelistingOther)
         .thenAccept(result -> {
            if (result.success()) {
               if (event.getGuild() != null) {
                  this.grantWhitelistedRole(event.getGuild(), targetDiscordId);
               }
               String logDesc = "Player **" + result.profile().exactName() + "** linked to <@" + targetDiscordId + "> by " +
                  (isAdminWhitelistingOther ? "<@" + event.getUser().getId() + "> (Staff)" : "<@" + targetDiscordId + "> (Self)");
               this.logWhitelistEvent("✅ Whitelist Added", logDesc, Color.decode("#2ECC71"), result.profile().exactName());

               MessageEmbed embed = this.buildSuccessEmbed(result.profile(), targetDiscordId);
               event.getHook().sendMessageEmbeds(embed).queue();
            } else {
               EmbedBuilder eb = new EmbedBuilder()
                  .setTitle("❌ Whitelist Failed")
                  .setDescription(result.message())
                  .setColor(Color.decode("#E74C3C"));
               event.getHook().sendMessageEmbeds(eb.build()).queue();
            }
         });
   }

   private void handleWhitelistRemove(SlashCommandInteractionEvent event) {
      OptionMapping targetOpt = event.getOption("target");
      String target = targetOpt == null ? "" : targetOpt.getAsString().trim();

      boolean isSelf = target.isEmpty() || target.equals(event.getUser().getId()) || target.equalsIgnoreCase(event.getUser().getName());

      if (isSelf) {
         if (!this.plugin.getConfig().getBoolean("settings.allow-self-unwhitelist", true)) {
            event.reply("Self-unwhitelisting is disabled on this server. Contact staff.").setEphemeral(true).queue();
            return;
         }
         target = event.getUser().getId();
      } else {
         if (!this.isAdmin(event.getMember())) {
            event.reply(this.whitelistManager.msg("no-permission", "You do not have permission to remove other users.")).setEphemeral(true).queue();
            return;
         }
      }

      event.deferReply(true).queue();

      final String finalTarget = target;
      final boolean finalIsSelf = isSelf;

      this.whitelistManager.unwhitelistAsync(finalTarget, event.getUser().getName()).thenAccept(result -> {
         if (result.success()) {
            if (event.getGuild() != null && result.entry() != null) {
               this.revokeWhitelistedRole(event.getGuild(), result.entry().discordId());
            }
            String logDesc = "Player **" + result.entry().minecraftUsername() + "** was unwhitelisted by " +
               (finalIsSelf ? "<@" + event.getUser().getId() + "> (Self)" : "<@" + event.getUser().getId() + "> (Staff)");
            this.logWhitelistEvent("🗑️ Whitelist Removed", logDesc, Color.decode("#E74C3C"), result.entry().minecraftUsername());

            EmbedBuilder eb = new EmbedBuilder()
               .setTitle("✅ Unwhitelisted Successfully")
               .setDescription(result.message())
               .setColor(Color.decode("#E67E22"))
               .setTimestamp(Instant.now());
            event.getHook().sendMessageEmbeds(eb.build()).queue();
         } else {
            EmbedBuilder eb = new EmbedBuilder()
               .setTitle("❌ Unwhitelist Failed")
               .setDescription(result.message())
               .setColor(Color.decode("#E74C3C"));
            event.getHook().sendMessageEmbeds(eb.build()).queue();
         }
      });
   }

   private void handleWhois(SlashCommandInteractionEvent event) {
      if (!this.canAccess(event.getMember(), "allow-public-whois")) {
         event.reply(this.whitelistManager.msg("no-permission", "You do not have permission to use whois.")).setEphemeral(true).queue();
         return;
      }

      OptionMapping targetOpt = event.getOption("target");
      String target = targetOpt == null ? event.getUser().getId() : targetOpt.getAsString().trim();

      event.deferReply(false).queue();

      WhitelistManager.DiscordEntry entry = this.whitelistManager.lookup(target);
      if (entry == null) {
         EmbedBuilder eb = new EmbedBuilder()
            .setTitle("Player Not Found")
            .setDescription("No registered whitelist record found matching `" + target + "`.\nMake sure the player has linked their account.")
            .setColor(Color.decode("#E74C3C"));
         event.getHook().sendMessageEmbeds(eb.build()).queue();
         return;
      }

      MessageEmbed embed = this.buildWhoisEmbed(entry);
      event.getHook().sendMessageEmbeds(embed).queue();
   }

   private void handleRegistered(SlashCommandInteractionEvent event) {
      if (!this.canAccess(event.getMember(), "allow-public-list")) {
         event.reply(this.whitelistManager.msg("no-permission", "You do not have permission to view the registered list.")).setEphemeral(true).queue();
         return;
      }

      int page = 1;
      OptionMapping pageOpt = event.getOption("page");
      if (pageOpt != null) {
         page = Math.max(1, pageOpt.getAsInt());
      }

      OptionMapping searchOpt = event.getOption("search");
      String search = searchOpt == null ? "" : searchOpt.getAsString().trim();

      List<WhitelistManager.DiscordEntry> matching = this.whitelistManager.searchEntries(search);
      int total = matching.size();
      int pageSize = 8;
      int totalPages = Math.max(1, (int) Math.ceil((double) total / pageSize));
      page = Math.min(page, totalPages);

      List<WhitelistManager.DiscordEntry> paged = this.whitelistManager.getPagedList(matching, page, pageSize);

      MessageEmbed embed = this.buildRegisteredEmbed(paged, page, totalPages, total, search);
      List<Button> buttons = this.getPaginationButtons(page, totalPages, search);

      event.replyEmbeds(embed).setActionRow(buttons).setEphemeral(true).queue();
   }

   private void handleOnline(SlashCommandInteractionEvent event) {
      if (!this.canAccess(event.getMember(), "allow-public-online")) {
         event.reply(this.whitelistManager.msg("no-permission", "You do not have permission to view online players.")).setEphemeral(true).queue();
         return;
      }

      event.deferReply(false).queue();

      Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
      int onlineCount = onlinePlayers.size();
      int maxPlayers = Bukkit.getMaxPlayers();
      String serverIp = this.plugin.getConfig().getString("server-ip", "play.yourserver.com");

      EmbedBuilder eb = new EmbedBuilder()
         .setTitle("🎮 Online Players (" + onlineCount + "/" + maxPlayers + ")")
         .setColor(Color.decode("#2ECC71"))
         .setThumbnail("https://mc-heads.net/avatar/MHF_Chest/128.png")
         .setTimestamp(Instant.now());

      if (onlineCount == 0) {
         eb.setDescription("No players are currently online.\nServer IP: `" + serverIp + "`");
      } else {
         StringBuilder sb = new StringBuilder();
         sb.append("Server IP: `").append(serverIp).append("`\n\n");
         for (Player p : onlinePlayers) {
            WhitelistManager.DiscordEntry entry = this.whitelistManager.getEntryByMinecraftName(p.getName());
            if (entry == null) {
               entry = this.whitelistManager.getEntryByUuid(p.getUniqueId().toString());
            }

            if (entry != null) {
               sb.append("• **").append(p.getName()).append("** ➔ <@").append(entry.discordId())
                 .append("> (`@").append(entry.discordName()).append("`)\n");
            } else {
               sb.append("• **").append(p.getName()).append("** ➔ *(Not linked to Discord)*\n");
            }
         }
         eb.setDescription(sb.toString());
      }

      eb.setFooter("Use /whois <player> for full details", null);
      event.getHook().sendMessageEmbeds(eb.build()).queue();
   }

   private void handleWhitelistPanel(SlashCommandInteractionEvent event) {
      if (!this.isAdmin(event.getMember())) {
         event.reply(this.whitelistManager.msg("no-permission", "You do not have permission to post the panel.")).setEphemeral(true).queue();
         return;
      }

      OptionMapping channelOpt = event.getOption("channel");
      TextChannel targetChannel = null;

      if (channelOpt != null) {
         GuildChannelUnion channelUnion = channelOpt.getAsChannel();
         if (channelUnion.getType() == ChannelType.TEXT) {
            targetChannel = channelUnion.asTextChannel();
         }
      }

      if (targetChannel == null && event.getChannel() instanceof TextChannel tc) {
         targetChannel = tc;
      }

      if (targetChannel == null) {
         event.reply("Could not identify a valid text channel to post to.").setEphemeral(true).queue();
         return;
      }

      this.postPanel(targetChannel);
      event.reply("✅ Whitelist panel posted to #" + targetChannel.getName()).setEphemeral(true).queue();
   }

   private void handleWhitelistReload(SlashCommandInteractionEvent event) {
      if (!this.isAdmin(event.getMember())) {
         event.reply(this.whitelistManager.msg("no-permission", "You do not have permission to reload the plugin.")).setEphemeral(true).queue();
         return;
      }

      this.plugin.reloadPluginConfig();
      event.reply("🔄 Plugin configuration and bot activity reloaded successfully.").setEphemeral(true).queue();
   }

   private void handleWhitelistUpdate(SlashCommandInteractionEvent event) {
      if (!this.isAdmin(event.getMember())) {
         event.reply(this.whitelistManager.msg("no-permission", "You do not have permission to manage updates.")).setEphemeral(true).queue();
         return;
      }

      OptionMapping actionOpt = event.getOption("action");
      String action = actionOpt == null ? "check" : actionOpt.getAsString().toLowerCase();

      event.deferReply(true).queue();

      if ("download".equals(action)) {
         this.plugin.getUpdateManager().downloadUpdate(null).thenAccept(res -> {
            if (res.success()) {
               EmbedBuilder eb = new EmbedBuilder()
                  .setTitle("✅ Update Downloaded Successfully")
                  .setDescription("Downloaded version **v" + this.plugin.getUpdateManager().getLatestVersion() + "** to the server's update folder.\nIt will automatically apply on the next server restart!")
                  .setColor(Color.decode("#2ECC71"))
                  .setTimestamp(Instant.now());
               event.getHook().sendMessageEmbeds(eb.build()).queue();
            } else {
               EmbedBuilder eb = new EmbedBuilder()
                  .setTitle("❌ Download Failed")
                  .setDescription(res.message())
                  .setColor(Color.decode("#E74C3C"));
               event.getHook().sendMessageEmbeds(eb.build()).queue();
            }
         });
      } else {
         this.plugin.getUpdateManager().checkForUpdates(true, null).thenAccept(res -> {
            if (res.error() != null) {
               EmbedBuilder eb = new EmbedBuilder()
                  .setTitle("Update Check Result")
                  .setDescription("Status: " + res.error())
                  .setColor(Color.decode("#E67E22"))
                  .addField("Current Version", "`v" + res.currentVersion() + "`", true);
               event.getHook().sendMessageEmbeds(eb.build()).queue();
            } else if (res.hasUpdate()) {
               EmbedBuilder eb = new EmbedBuilder()
                  .setTitle("🚀 Update Available: v" + res.latestVersion())
                  .setDescription("A new version of **DiscordWhitelist** is available to install!")
                  .setColor(Color.decode("#F39C12"))
                  .addField("Current Version", "`v" + res.currentVersion() + "`", true)
                  .addField("Latest Version", "`v" + res.latestVersion() + "`", true)
                  .addField("Download Link", "[View Release on GitHub](" + this.plugin.getUpdateManager().getReleaseHtmlUrl() + ")", false);

               if (res.notes() != null && !res.notes().isBlank()) {
                  String notes = res.notes().length() > 400 ? res.notes().substring(0, 400) + "..." : res.notes();
                  eb.addField("Release Notes", notes, false);
               }

               eb.setFooter("Run /whitelist update action:download to download automatically", null);
               event.getHook().sendMessageEmbeds(eb.build()).queue();
            } else {
               EmbedBuilder eb = new EmbedBuilder()
                  .setTitle("✅ DiscordWhitelist is Up to Date")
                  .setDescription("You are running the latest version (`v" + res.currentVersion() + "`).")
                  .setColor(Color.decode("#2ECC71"));
               event.getHook().sendMessageEmbeds(eb.build()).queue();
            }
         });
      }
   }

   private void handleServerStatus(SlashCommandInteractionEvent event) {
      event.deferReply(false).queue();

      int onlineCount = Bukkit.getOnlinePlayers().size();
      int maxPlayers = Bukkit.getMaxPlayers();
      String version = Bukkit.getServer().getName() + " " + Bukkit.getServer().getMinecraftVersion();
      String serverIp = this.plugin.getConfig().getString("server-ip", "play.yourserver.com");
      boolean whitelistOn = Bukkit.getServer().hasWhitelist();
      long ping = event.getJDA().getGatewayPing();

      EmbedBuilder eb = new EmbedBuilder()
         .setTitle("🖥️ Minecraft Server Status")
         .setColor(Color.decode("#9B59B6"))
         .addField("Server IP", "`" + serverIp + "`", true)
         .addField("Players Online", "**" + onlineCount + "** / **" + maxPlayers + "**", true)
         .addField("Whitelist", whitelistOn ? "✅ Active" : "❌ Disabled", true)
         .addField("Version", "`" + version + "`", true)
         .addField("Bot Ping", ping + " ms", true)
         .setTimestamp(Instant.now());

      if (onlineCount > 0) {
         String list = Bukkit.getOnlinePlayers().stream()
            .map(Player::getName)
            .limit(25)
            .collect(Collectors.joining(", "));
         if (onlineCount > 25) {
            list += " and " + (onlineCount - 25) + " more...";
         }
         eb.addField("Online Players (" + onlineCount + ")", list, false);
      }

      event.getHook().sendMessageEmbeds(eb.build()).queue();
   }

   @Override
   public void onButtonInteraction(ButtonInteractionEvent event) {
      String btnId = event.getComponentId();

      if (btnId.startsWith("dwl_page_prev:") || btnId.startsWith("dwl_page_next:")) {
         String[] parts = btnId.split(":", 3);
         int targetPage = 1;
         try {
            targetPage = Integer.parseInt(parts[1]);
         } catch (NumberFormatException ignored) {}

         String search = parts.length > 2 ? parts[2] : "";

         List<WhitelistManager.DiscordEntry> matching = this.whitelistManager.searchEntries(search);
         int total = matching.size();
         int pageSize = 8;
         int totalPages = Math.max(1, (int) Math.ceil((double) total / pageSize));
         targetPage = Math.max(1, Math.min(targetPage, totalPages));

         List<WhitelistManager.DiscordEntry> paged = this.whitelistManager.getPagedList(matching, targetPage, pageSize);
         MessageEmbed embed = this.buildRegisteredEmbed(paged, targetPage, totalPages, total, search);
         List<Button> buttons = this.getPaginationButtons(targetPage, totalPages, search);

         event.editMessageEmbeds(embed).setActionRow(buttons).queue();
         return;
      }

      switch (btnId) {
         case BTN_OPEN_MODAL -> {
            if (!this.hasRequiredRole(event.getMember())) {
               String reqRoleId = this.plugin.getConfig().getString("roles.required-role-id", "");
               String msg = this.whitelistManager.msg("missing-required-role", "You must have the required role to whitelist.")
                  .replace("%role_id%", reqRoleId);
               event.reply(msg).setEphemeral(true).queue();
               return;
            }

            if (!this.plugin.getConfig().getBoolean("settings.allow-self-unwhitelist", true)
               && this.whitelistManager.getEntryByDiscordId(event.getUser().getId()) != null) {
               WhitelistManager.DiscordEntry entry = this.whitelistManager.getEntryByDiscordId(event.getUser().getId());
               String msg = this.whitelistManager.msg("already-used", "You've already whitelisted the account %username%.")
                  .replace("%username%", entry.minecraftUsername());
               event.reply(msg).setEphemeral(true).queue();
               return;
            }

            TextInput usernameInput = TextInput.create(INPUT_USERNAME, "Minecraft Username (Case-Sensitive)", TextInputStyle.SHORT)
               .setPlaceholder("e.g. Notch")
               .setMinLength(2)
               .setMaxLength(16)
               .setRequired(true)
               .build();

            Modal modal = Modal.create(MODAL_SUBMIT, "Server Whitelist Request")
               .addActionRow(usernameInput)
               .build();

            event.replyModal(modal).queue();
         }
         case BTN_CHECK_STATUS -> {
            event.deferReply(true).queue();
            WhitelistManager.DiscordEntry entry = this.whitelistManager.getEntryByDiscordId(event.getUser().getId());
            if (entry == null) {
               EmbedBuilder eb = new EmbedBuilder()
                  .setTitle("Not Whitelisted")
                  .setDescription("You do not have a Minecraft account whitelisted on this server yet.\nClick **Whitelist Me** to register!")
                  .setColor(Color.decode("#F39C12"));
               event.getHook().sendMessageEmbeds(eb.build()).queue();
            } else {
               event.getHook().sendMessageEmbeds(this.buildWhoisEmbed(entry)).queue();
            }
         }
         case BTN_VIEW_REGISTERED -> {
            if (!this.canAccess(event.getMember(), "allow-public-list")) {
               event.reply(this.whitelistManager.msg("no-permission", "You do not have permission to view registered accounts.")).setEphemeral(true).queue();
               return;
            }

            List<WhitelistManager.DiscordEntry> matching = this.whitelistManager.searchEntries("");
            int total = matching.size();
            int pageSize = 8;
            int totalPages = Math.max(1, (int) Math.ceil((double) total / pageSize));
            List<WhitelistManager.DiscordEntry> paged = this.whitelistManager.getPagedList(matching, 1, pageSize);

            MessageEmbed embed = this.buildRegisteredEmbed(paged, 1, totalPages, total, "");
            List<Button> buttons = this.getPaginationButtons(1, totalPages, "");

            event.replyEmbeds(embed).setActionRow(buttons).setEphemeral(true).queue();
         }
         case BTN_UNLINK_SELF -> {
            if (!this.plugin.getConfig().getBoolean("settings.allow-self-unwhitelist", true)) {
               event.reply("Self-unwhitelisting is disabled on this server. Please contact staff.").setEphemeral(true).queue();
               return;
            }

            event.deferReply(true).queue();
            this.whitelistManager.unwhitelistAsync(event.getUser().getId(), event.getUser().getName()).thenAccept(result -> {
               if (result.success()) {
                  if (event.getGuild() != null && result.entry() != null) {
                     this.revokeWhitelistedRole(event.getGuild(), result.entry().discordId());
                  }
                  String logDesc = "Player **" + result.entry().minecraftUsername() + "** was self-unlinked by <@" + event.getUser().getId() + ">";
                  this.logWhitelistEvent("🗑️ Whitelist Removed", logDesc, Color.decode("#E74C3C"), result.entry().minecraftUsername());

                  EmbedBuilder eb = new EmbedBuilder()
                     .setTitle("Account Unlinked")
                     .setDescription(result.message())
                     .setColor(Color.decode("#E67E22"));
                  event.getHook().sendMessageEmbeds(eb.build()).queue();
               } else {
                  EmbedBuilder eb = new EmbedBuilder()
                     .setTitle("Unlink Failed")
                     .setDescription(result.message())
                     .setColor(Color.decode("#E74C3C"));
                  event.getHook().sendMessageEmbeds(eb.build()).queue();
               }
            });
         }
      }
   }

   @Override
   public void onModalInteraction(ModalInteractionEvent event) {
      if (MODAL_SUBMIT.equals(event.getModalId())) {
         ModalMapping mapping = event.getValue(INPUT_USERNAME);
         String username = mapping == null ? null : mapping.getAsString().trim();
         String discordId = event.getUser().getId();
         String discordName = event.getUser().getName();

         event.deferReply(true).queue();

         this.whitelistManager.whitelistPlayerAsync(discordId, discordName, username, false).thenAccept(result -> {
            if (result.success()) {
               if (event.getGuild() != null) {
                  this.grantWhitelistedRole(event.getGuild(), discordId);
               }
               String logDesc = "Player **" + result.profile().exactName() + "** whitelisted via panel modal by <@" + discordId + ">";
               this.logWhitelistEvent("✅ Whitelist Added", logDesc, Color.decode("#2ECC71"), result.profile().exactName());

               MessageEmbed embed = this.buildSuccessEmbed(result.profile(), discordId);
               event.getHook().sendMessageEmbeds(embed).queue();
            } else {
               EmbedBuilder eb = new EmbedBuilder()
                  .setTitle("❌ Whitelist Request Failed")
                  .setDescription(result.message())
                  .setColor(Color.decode("#E74C3C"));
               event.getHook().sendMessageEmbeds(eb.build()).queue();
            }
         });
      }
   }

   @Override
   public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
      if (!this.plugin.getConfig().getBoolean("settings.remove-whitelist-on-guild-leave", false)) {
         return;
      }

      String discordId = event.getUser().getId();
      WhitelistManager.DiscordEntry entry = this.whitelistManager.getEntryByDiscordId(discordId);
      if (entry != null) {
         this.whitelistManager.unwhitelistAsync(discordId, "Guild Leave").thenAccept(result -> {
            if (result.success()) {
               String logDesc = "Player **" + entry.minecraftUsername() + "** was automatically removed from whitelist because <@" + discordId + "> left the Discord server.";
               this.logWhitelistEvent("🚪 Member Left Server", logDesc, Color.decode("#E74C3C"), entry.minecraftUsername());
               this.plugin.getLogger().info("Automatically unwhitelisted " + entry.minecraftUsername() + " (user left Discord server).");
            }
         });
      }
   }

   public void postPanel(TextChannel channel) {
      if (channel == null) {
         String channelId = this.plugin.getConfig().getString("channel-id", "");
         if (this.plugin.getJda() != null && channelId != null && !channelId.isBlank()) {
            channel = this.plugin.getJda().getTextChannelById(channelId);
         }
      }

      if (channel == null) {
         this.plugin.getLogger().warning("Could not find Discord text channel for the whitelist panel. Check channel-id in config.yml.");
         return;
      }

      MessageEmbed embed = this.buildPanelEmbed();
      List<Button> buttons = this.getPanelButtons();

      final String channelName = channel.getName();
      channel.sendMessageEmbeds(embed).setActionRow(buttons).queue(
         ok -> this.plugin.getLogger().info("Whitelist panel successfully posted to #" + channelName),
         err -> this.plugin.getLogger().warning("Failed to post whitelist panel: " + err.getMessage())
      );
   }

   public MessageEmbed buildPanelEmbed() {
      String title = this.plugin.getConfig().getString("embed.title", "Server Whitelist");
      String desc = this.plugin.getConfig().getString("embed.description", "Click below to get whitelisted.");
      String colorHex = this.plugin.getConfig().getString("embed.color", "#2ECC71");
      String thumb = this.plugin.getConfig().getString("embed.thumbnail-url", "");
      String footer = this.plugin.getConfig().getString("embed.footer-text", "DiscordWhitelist");

      EmbedBuilder eb = new EmbedBuilder()
         .setTitle(title)
         .setDescription(desc)
         .setColor(this.parseColor(colorHex, new Color(46, 204, 113)))
         .setFooter(footer, null)
         .setTimestamp(Instant.now());

      if (thumb != null && !thumb.isBlank()) {
         eb.setThumbnail(thumb);
      }

      return eb.build();
   }

   public List<Button> getPanelButtons() {
      List<Button> buttons = new ArrayList<>();
      buttons.add(Button.primary(BTN_OPEN_MODAL, "Whitelist Me").withEmoji(Emoji.fromUnicode("🎮")));
      buttons.add(Button.secondary(BTN_CHECK_STATUS, "My Status").withEmoji(Emoji.fromUnicode("🔍")));
      buttons.add(Button.secondary(BTN_VIEW_REGISTERED, "Registered").withEmoji(Emoji.fromUnicode("📋")));

      if (this.plugin.getConfig().getBoolean("settings.allow-self-unwhitelist", true)) {
         buttons.add(Button.danger(BTN_UNLINK_SELF, "Unlink").withEmoji(Emoji.fromUnicode("❌")));
      }
      return buttons;
   }

   public MessageEmbed buildSuccessEmbed(UuidService.ProfileResult profile, String discordId) {
      String serverIp = this.plugin.getConfig().getString("server-ip", "play.yourserver.com");
      String successMsg = this.whitelistManager.msg("success", "You're whitelisted as **%username%**!")
         .replace("%username%", profile.exactName())
         .replace("%server_ip%", serverIp);

      return new EmbedBuilder()
         .setTitle("✅ Whitelist Successful!")
         .setDescription(successMsg)
         .setColor(Color.decode("#2ECC71"))
         .addField("Player", "`" + profile.exactName() + "`", true)
         .addField("UUID", "`" + profile.uuid().toString() + "`", true)
         .addField("Server IP", "`" + serverIp + "`", true)
         .addField("Discord Account", "<@" + discordId + ">", true)
         .setThumbnail("https://mc-heads.net/avatar/" + profile.exactName() + "/128.png")
         .setFooter("Launch Minecraft to join!", null)
         .setTimestamp(Instant.now())
         .build();
   }

   public MessageEmbed buildWhoisEmbed(WhitelistManager.DiscordEntry entry) {
      UUID uuid;
      try {
         uuid = UUID.fromString(entry.uuid());
      } catch (Exception e) {
         uuid = UuidService.offlineUUID(entry.minecraftUsername());
      }

      OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
      boolean isOnline = op.isOnline();
      String onlineStatus = isOnline ? "🟢 Online now" : "🔴 Offline";
      long unixSeconds = entry.timestamp() / 1000L;
      String serverIp = this.plugin.getConfig().getString("server-ip", "play.yourserver.com");

      return new EmbedBuilder()
         .setTitle("👤 Whois: " + entry.minecraftUsername())
         .setColor(isOnline ? Color.decode("#2ECC71") : Color.decode("#3498DB"))
         .setThumbnail("https://mc-heads.net/avatar/" + entry.minecraftUsername() + "/128.png")
         .addField("Minecraft Username", "**" + entry.minecraftUsername() + "**", true)
         .addField("Server Status", onlineStatus, true)
         .addField("Server IP", "`" + serverIp + "`", true)
         .addField("Discord Account", "<@" + entry.discordId() + ">", true)
         .addField("Discord Tag & ID", "`@" + entry.discordName() + "` • `" + entry.discordId() + "`", false)
         .addField("UUID", "`" + entry.uuid() + "`", false)
         .addField("Whitelisted Date", "<t:" + unixSeconds + ":F> (<t:" + unixSeconds + ":R>)", false)
         .setFooter("DiscordWhitelist • Lookup", null)
         .setTimestamp(Instant.now())
         .build();
   }

   public MessageEmbed buildRegisteredEmbed(List<WhitelistManager.DiscordEntry> entries, int page, int totalPages, int totalCount, String search) {
      EmbedBuilder eb = new EmbedBuilder()
         .setTitle("📋 Registered Players (" + totalCount + " total)")
         .setColor(Color.decode("#3498DB"))
         .setTimestamp(Instant.now());

      if (search != null && !search.isBlank()) {
         eb.setDescription("Filtered by search: **`" + search + "`** • Page " + page + " of " + totalPages);
      } else {
         eb.setDescription("Directory of linked Minecraft & Discord players • Page " + page + " of " + totalPages);
      }

      if (entries.isEmpty()) {
         eb.appendDescription("\n\n*No registered players found matching criteria.*");
      } else {
         StringBuilder sb = new StringBuilder("\n\n");
         int idx = (page - 1) * 8 + 1;
         for (WhitelistManager.DiscordEntry e : entries) {
            sb.append("`").append(idx++).append(".` ")
              .append("**").append(e.minecraftUsername()).append("**")
              .append(" ➔ <@").append(e.discordId()).append("> (`@").append(e.discordName()).append("`)\n");
         }
         eb.appendDescription(sb.toString());
      }

      eb.setFooter("Use /whois <player> for full player details • Page " + page + "/" + totalPages, null);
      return eb.build();
   }

   public List<Button> getPaginationButtons(int page, int totalPages, String search) {
      List<Button> buttons = new ArrayList<>();
      String searchKey = (search == null || search.isBlank()) ? "" : ":" + search;

      buttons.add(Button.secondary("dwl_page_prev:" + (page - 1) + searchKey, "◀ Previous").withDisabled(page <= 1));
      buttons.add(Button.secondary("dwl_page_curr:" + page, "Page " + page + "/" + Math.max(1, totalPages)).asDisabled());
      buttons.add(Button.secondary("dwl_page_next:" + (page + 1) + searchKey, "Next ▶").withDisabled(page >= totalPages));

      return buttons;
   }

   public void grantWhitelistedRole(Guild guild, String discordId) {
      String roleId = this.plugin.getConfig().getString("roles.whitelisted-role-id", "");
      if (guild == null || roleId == null || roleId.isBlank()) return;

      Role role = guild.getRoleById(roleId);
      if (role == null) return;

      guild.retrieveMemberById(discordId).queue(member -> {
         if (!member.getRoles().contains(role)) {
            guild.addRoleToMember(member, role).queue(
               ok -> this.plugin.getLogger().info("Granted whitelist role to " + member.getUser().getAsTag()),
               err -> this.plugin.getLogger().warning("Failed to grant role: " + err.getMessage())
            );
         }
      }, err -> {});
   }

   public void revokeWhitelistedRole(Guild guild, String discordId) {
      String roleId = this.plugin.getConfig().getString("roles.whitelisted-role-id", "");
      if (guild == null || roleId == null || roleId.isBlank()) return;

      Role role = guild.getRoleById(roleId);
      if (role == null) return;

      guild.retrieveMemberById(discordId).queue(member -> {
         if (member.getRoles().contains(role)) {
            guild.removeRoleFromMember(member, role).queue(
               ok -> this.plugin.getLogger().info("Revoked whitelist role from " + member.getUser().getAsTag()),
               err -> this.plugin.getLogger().warning("Failed to revoke role: " + err.getMessage())
            );
         }
      }, err -> {});
   }

   public boolean hasRequiredRole(Member member) {
      String reqRoleId = this.plugin.getConfig().getString("roles.required-role-id", "");
      if (reqRoleId == null || reqRoleId.isBlank()) {
         return true;
      }
      if (member == null) {
         return true;
      }
      return member.getRoles().stream().anyMatch(r -> r.getId().equals(reqRoleId));
   }

   public boolean isAdmin(Member member) {
      if (member == null) return false;
      if (member.hasPermission(Permission.ADMINISTRATOR) || member.hasPermission(Permission.MANAGE_SERVER)) {
         return true;
      }
      List<String> adminRoleIds = this.plugin.getConfig().getStringList("roles.admin-role-ids");
      for (Role role : member.getRoles()) {
         if (adminRoleIds.contains(role.getId())) {
            return true;
         }
      }
      return false;
   }

   public boolean canAccess(Member member, String configPermissionKey) {
      if (this.isAdmin(member)) {
         return true;
      }
      return this.plugin.getConfig().getBoolean("permissions." + configPermissionKey, true);
   }

   public void logWhitelistEvent(String title, String description, Color color, String username) {
      if (!this.plugin.getConfig().getBoolean("logging.enabled", false)) return;

      String logChannelId = this.plugin.getConfig().getString("logging.channel-id", "");
      if (logChannelId == null || logChannelId.isBlank() || this.plugin.getJda() == null) return;

      TextChannel channel = this.plugin.getJda().getTextChannelById(logChannelId);
      if (channel == null) return;

      EmbedBuilder eb = new EmbedBuilder()
         .setTitle(title)
         .setDescription(description)
         .setColor(color)
         .setTimestamp(Instant.now());

      if (username != null && !username.isBlank()) {
         eb.setThumbnail("https://mc-heads.net/avatar/" + username + "/64.png");
      }

      channel.sendMessageEmbeds(eb.build()).queue(null, err -> {});
   }

   public Color parseColor(String hex, Color fallback) {
      if (hex == null || hex.isBlank()) return fallback;
      try {
         String clean = hex.startsWith("#") ? hex : "#" + hex;
         return Color.decode(clean);
      } catch (Exception e) {
         return fallback;
      }
   }
}

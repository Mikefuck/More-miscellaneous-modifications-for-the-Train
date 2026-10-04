package com.habitrain.lottery.prelaunch.late;

import com.habitrain.lottery.prelaunch.AutoModpackState;
import com.habitrain.lottery.prelaunch.PrelaunchConfig;
import com.habitrain.lottery.prelaunch.PrelaunchLog;
import com.habitrain.lottery.prelaunch.ServerAddressParser;
import com.habitrain.lottery.prelaunch.ServerListReader;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.util.Arguments;
import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.AutoModpackProtocol;
import pl.skidam.automodpack_core.utils.ClientCacheUtils;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;

/**
 * Runs right after AutoModpack's preload (which the early adapter told not to update), shows the
 * picker and then drives AutoModpack's own client code: selecting the modpack, fetching the content
 * list, downloading with its {@link ModpackUpdater} and finally its usual apply/restart step.
 *
 * <p>Two things AutoModpack cannot do before the game starts are filled in here: obtaining the
 * download secret for a server that was never joined (see {@link McLoginClient}), and asking the
 * player about the host certificate and about JAR files that are not on Modrinth/CurseForge
 * (AutoModpack's preload silently aborts on those, its in-game screen asks instead).
 */
final class PrelaunchUpdater {

    private PrelaunchUpdater() {
    }

    static void run() {
        if (GlobalVariables.clientConfig == null) {
            PrelaunchLog.warn("AutoModpack client config not loaded, prelaunch picker skipped");
            AutoModpackState.restoreFlagFile();
            return;
        }
        boolean updateOnLaunch = restoreLaunchUpdateFlag();
        boolean actionStarted = false;
        try {
            FabricLoader loader = FabricLoader.getInstance();
            PrelaunchConfig config = PrelaunchConfig.load(loader.getConfigDir());
            List<PickerOption> options = buildOptions(
                    ServerListReader.read(loader.getGameDir().resolve("servers.dat")));
            if (options.isEmpty()) {
                actionStarted = true;
                runDefault(updateOnLaunch);
                return;
            }
            // With no modpack selected yet the default does nothing, so wait for the player instead.
            int autoContinue = options.stream().anyMatch(PickerOption::selected) ? config.autoContinueSeconds : 0;
            PickerDialog.Choice choice = PickerDialog.show(options, updateOnLaunch, autoContinue);
            if (choice.disableNextTime()) {
                config.save(false);
            }
            actionStarted = true;
            switch (choice.action()) {
                case UPDATE -> {
                    PrelaunchLog.info("Prelaunch picker: updating from '%s' (%s)",
                            choice.option().title(), choice.option().address());
                    if (!updateFrom(choice.option())) {
                        loadSelected();
                    }
                }
                case SKIP -> {
                    PrelaunchLog.info("Prelaunch picker: skipping modpack update for this launch");
                    loadSelected();
                }
                case DEFAULT -> {
                    PrelaunchLog.info("Prelaunch picker: no choice made, using AutoModpack defaults");
                    runDefault(updateOnLaunch);
                }
            }
        } catch (Throwable t) {
            PrelaunchLog.error("Prelaunch picker failed", t);
            if (!actionStarted) {
                runDefault(updateOnLaunch);
            }
        }
    }

    /** Undoes the early adapter's one-launch override, both on disk and in AutoModpack's memory. */
    private static boolean restoreLaunchUpdateFlag() {
        Boolean original = AutoModpackState.restoreFlagFile();
        boolean value = original != null ? original : GlobalVariables.clientConfig.updateSelectedModpackOnLaunch;
        GlobalVariables.clientConfig.updateSelectedModpackOnLaunch = value;
        return value;
    }

    static List<PickerOption> buildOptions(List<ServerListReader.ServerEntry> servers) {
        Map<String, Jsons.ModpackAddresses> installed = GlobalVariables.clientConfig.installedModpacks == null
                ? Map.of() : GlobalVariables.clientConfig.installedModpacks;
        String selected = GlobalVariables.clientConfig.selectedModpack == null ? "" : GlobalVariables.clientConfig.selectedModpack;
        Map<String, String> modpackByServer = new HashMap<>();
        installed.forEach((name, addresses) -> {
            if (addresses != null && addresses.serverAddress != null) {
                modpackByServer.putIfAbsent(ServerAddressParser.normalizeStored(
                        addresses.serverAddress.getHostString() + ":" + addresses.serverAddress.getPort()), name);
            }
        });

        List<PickerOption> options = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        for (ServerListReader.ServerEntry server : servers) {
            String name = modpackByServer.get(ServerAddressParser.parse(server.ip()).normalized());
            if (name != null) {
                listed.add(name);
            }
            options.add(option(server.name(), server.ip(), name, selected));
        }
        // Installed modpacks whose server is no longer in the multiplayer list.
        installed.forEach((name, addresses) -> {
            if (!listed.contains(name) && addresses != null && addresses.serverAddress != null) {
                String address = addresses.serverAddress.getHostString() + ":" + addresses.serverAddress.getPort();
                options.add(option("整合包 " + name, address, name, selected));
            }
        });
        return options;
    }

    private static PickerOption option(String title, String address, String modpack, String selected) {
        long timestamp = -1L;
        if (modpack != null) {
            Secrets.Secret secret = SecretsStore.getClientSecret(modpack);
            if (secret != null && secret.secret() != null && !secret.secret().isBlank() && secret.timestamp() != null) {
                timestamp = secret.timestamp();
            }
        }
        return new PickerOption(title, address, modpack, timestamp, modpack != null && modpack.equals(selected));
    }

    // ---- actions ----

    /** What AutoModpack's preload would have done on its own. */
    private static void runDefault(boolean updateOnLaunch) {
        if (!updateOnLaunch) {
            return;
        }
        Selected selected = Selected.current();
        if (selected == null) {
            return;
        }
        Jsons.ModpackContentFields latest = ConfigTools.loadModpackContent(
                selected.dir.resolve(GlobalVariables.hostModpackContentFile.getFileName()));
        if (selected.secret != null) {
            Optional<Jsons.ModpackContentFields> fetched =
                    ModpackUtils.requestServerModpackContent(selected.addresses, selected.secret, false);
            if (fetched.isPresent()) {
                latest = fetched.get();
            }
        }
        ClientCacheUtils.deleteDummyFiles();
        new ModpackUpdater(latest, selected.addresses, selected.secret, selected.dir).processModpackUpdate(null);
    }

    /** Loads the already installed, selected modpack without touching the network. */
    private static void loadSelected() {
        Selected selected = Selected.current();
        if (selected == null) {
            return;
        }
        ClientCacheUtils.deleteDummyFiles();
        // Without server content AutoModpack only applies and loads what is on disk.
        new ModpackUpdater(null, selected.addresses, selected.secret, selected.dir).processModpackUpdate(null);
    }

    /** @return false when nothing was applied and the caller should load the current modpack */
    private static boolean updateFrom(PickerOption option) {
        ProgressWindow progress = ProgressWindow.open("正在连接服务器 " + option.address() + " …");
        try {
            Target target = null;
            boolean viaLogin = false;
            if (option.installedName() != null && option.secretUsable(System.currentTimeMillis() / 1000L)) {
                target = Target.installed(option.installedName());
            }
            if (target == null) {
                progress.status("正在登录服务器以获取整合包下载凭证…");
                target = Target.login(option.address());
                viaLogin = true;
            }

            progress.status("正在校验整合包服务器证书…");
            if (!ensureTrusted(target.addresses, progress)) {
                return false;
            }
            progress.status("正在获取整合包文件清单…");
            Optional<Jsons.ModpackContentFields> content =
                    ModpackUtils.requestServerModpackContent(target.addresses, target.secret, false);
            if (content.isEmpty() && !viaLogin) {
                // The stored secret may have been revoked or the server uses a shorter lifetime.
                progress.status("下载凭证已失效，正在重新登录服务器…");
                target = Target.login(option.address());
                if (!ensureTrusted(target.addresses, progress)) {
                    return false;
                }
                content = ModpackUtils.requestServerModpackContent(target.addresses, target.secret, false);
            }
            if (content.isEmpty()) {
                progress.close();
                Ui.error("无法从服务器获取整合包文件清单（详见游戏日志 logs/latest.log 中 AutoModpack 的报错）。<br>"
                        + "游戏将使用当前已安装的整合包继续启动。");
                return false;
            }
            Jsons.ModpackContentFields serverContent = content.get();

            progress.status("正在比对本地文件…");
            ModpackUtils.UpdateCheckResult result = ModpackUtils.isUpdate(serverContent, target.dir);
            if (result.requiresUpdate()) {
                progress.hide();
                if (!confirmDownload(option, serverContent, result.filesToUpdate())) {
                    progress.close();
                    return false;
                }
                progress.show();
            }

            // Selection and secret storage happen in AutoModpack's own apply step, exactly as after
            // an in-game join.
            GlobalVariables.selectedModpackDir = target.dir;
            ClientCacheUtils.deleteDummyFiles();
            ModpackUpdater updater = new ModpackUpdater(serverContent, target.addresses, target.secret, target.dir);
            if (!result.requiresUpdate()) {
                progress.close();
                updater.processModpackUpdate(result);
                return true;
            }

            // AutoModpack's preload refuses JARs without a public Modrinth/CurseForge match; the player
            // just confirmed them, so go straight to the download step like its in-game screen does.
            Path contentFile = target.dir.resolve(GlobalVariables.hostModpackContentFile.getFileName());
            setModpackContentFile(updater, contentFile);
            updater.fullDownload = !Files.exists(contentFile);
            progress.status("正在准备下载…");
            progress.follow(() -> updater.downloadManager);
            // On success AutoModpack applies the modpack; if mods changed it shows its own
            // "restart your game" window and exits the process.
            updater.startUpdate(result.filesToUpdate());
            progress.close();
            if (!updater.failedDownloads.isEmpty()) {
                Ui.error("有 " + updater.failedDownloads.size() + " 个文件下载失败（详见 logs/latest.log）。<br>"
                        + "可以重新启动游戏再试一次。");
            }
            return true;
        } catch (McLoginClient.LoginException e) {
            PrelaunchLog.error("Prelaunch login failed", e);
            progress.close();
            Ui.error("登录服务器获取下载凭证失败：<br>" + Ui.escape(e.getMessage())
                    + "<br><br>游戏将使用当前已安装的整合包继续启动；也可以进入游戏后直接加入该服务器下载。");
            return false;
        } catch (Exception e) {
            PrelaunchLog.error("Prelaunch update failed", e);
            progress.close();
            Ui.error("启动前更新失败：" + Ui.escape(String.valueOf(e.getMessage()))
                    + "<br>游戏将使用当前已安装的整合包继续启动。");
            return false;
        } finally {
            progress.close();
        }
    }

    private static boolean confirmDownload(PickerOption option, Jsons.ModpackContentFields content,
                                           Set<Jsons.ModpackContentFields.ModpackContentItem> files) {
        if (files.isEmpty()) {
            return true;
        }
        long bytes = 0L;
        int jars = 0;
        StringBuilder list = new StringBuilder();
        List<Jsons.ModpackContentFields.ModpackContentItem> sorted = new ArrayList<>(files);
        sorted.sort((a, b) -> a.file.compareTo(b.file));
        for (Jsons.ModpackContentFields.ModpackContentItem item : sorted) {
            try {
                bytes += Long.parseLong(item.size);
            } catch (NumberFormatException ignored) {
                // Size is informational only.
            }
            boolean jar = item.file.endsWith(".jar");
            if (jar) {
                jars++;
            }
            list.append(jar ? "[mod] " : "      ").append(item.file).append('\n');
        }
        String name = content.modpackName == null || content.modpackName.isBlank() ? option.title() : content.modpackName;
        return Ui.confirm("将从服务器「" + Ui.escape(option.title()) + "」下载整合包「" + Ui.escape(name) + "」的 "
                        + files.size() + " 个文件（约 " + Math.max(1L, bytes / 1024L / 1024L) + " MB），其中 mod "
                        + jars + " 个。<br>mod 会在游戏中被加载运行，请只在信任该服务器时继续。",
                list.toString(), "下载并安装", "取消");
    }

    /**
     * Makes sure the host certificate is in AutoModpack's known hosts, asking the player on first
     * contact (AutoModpack only asks inside the game).
     */
    private static boolean ensureTrusted(Jsons.ModpackAddresses addresses, ProgressWindow progress) {
        if (GlobalVariables.knownHosts == null) {
            GlobalVariables.knownHosts = new Jsons.KnownHostsFields();
        }
        if (GlobalVariables.knownHosts.hosts == null) {
            GlobalVariables.knownHosts.hosts = new HashMap<>();
        }
        String host = addresses.hostAddress.getHostString();
        Function<X509Certificate, Boolean> callback = certificate -> {
            String fingerprint;
            try {
                fingerprint = NetUtils.getFingerprint(certificate);
            } catch (Exception e) {
                return false;
            }
            String known = GlobalVariables.knownHosts.hosts.get(host);
            if (fingerprint.equals(known)) {
                return true;
            }
            progress.hide();
            String warning = known == null
                    ? "首次连接整合包服务器 <b>" + Ui.escape(host) + "</b>，需要确认它的证书。"
                    : "<b><font color='#B00020'>整合包服务器 " + Ui.escape(host) + " 的证书与上次不同！</font></b>"
                    + "如果服务器没有重装过，可能存在中间人攻击。";
            boolean trusted = Ui.confirm(warning + "<br>证书指纹（SHA-256）见下方，服务器后台日志中 AutoModpack 也会打印"
                            + " “Certificate fingerprint”，可让管理员核对。",
                    fingerprint, "信任并继续", "取消");
            progress.show();
            if (trusted) {
                GlobalVariables.knownHosts.hosts.put(host, fingerprint);
                ConfigTools.save(GlobalVariables.knownHostsFile, GlobalVariables.knownHosts);
            }
            return trusted;
        };
        try (DownloadClient client = DownloadClient.tryCreate(addresses, null, 1, callback)) {
            if (client == null) {
                Ui.error("无法建立与整合包服务器 " + Ui.escape(host) + " 的安全连接（证书未被信任或服务器无响应）。<br>"
                        + "游戏将使用当前已安装的整合包继续启动。");
                return false;
            }
            return true;
        }
    }

    private static void setModpackContentFile(ModpackUpdater updater, Path contentFile) throws ReflectiveOperationException {
        // startUpdate() expects processModpackUpdate() to have set this; we skip that method on purpose.
        Field field = ModpackUpdater.class.getDeclaredField("modpackContentFile");
        field.setAccessible(true);
        field.set(updater, contentFile);
    }

    // ---- targets ----

    private record Selected(Path dir, Jsons.ModpackAddresses addresses, Secrets.Secret secret) {

        static Selected current() {
            String name = GlobalVariables.clientConfig.selectedModpack;
            if (name == null || name.isBlank() || GlobalVariables.clientConfig.installedModpacks == null) {
                return null;
            }
            Jsons.ModpackAddresses addresses = GlobalVariables.clientConfig.installedModpacks.get(name);
            if (addresses == null || addresses.isAnyEmpty()) {
                return null;
            }
            Path dir = GlobalVariables.modpacksDir.resolve(name);
            GlobalVariables.selectedModpackDir = dir;
            return new Selected(dir, addresses, SecretsStore.getClientSecret(name));
        }
    }

    private record Target(Path dir, Jsons.ModpackAddresses addresses, Secrets.Secret secret) {

        static Target installed(String name) {
            Jsons.ModpackAddresses addresses = GlobalVariables.clientConfig.installedModpacks.get(name);
            Secrets.Secret secret = SecretsStore.getClientSecret(name);
            if (addresses == null || addresses.isAnyEmpty() || secret == null) {
                return null;
            }
            return new Target(GlobalVariables.modpacksDir.resolve(name), addresses, secret);
        }

        /** Mirrors AutoModpack's {@code DataC2SPacket} handling of a fresh join. */
        static Target login(String typedAddress) throws Exception {
            ServerAddressParser.HostPort typed = ServerAddressParser.parse(typedAddress);
            ServerAddressParser.HostPort connect = typed.explicitPort()
                    ? typed : SrvResolver.resolve(typed.host()).orElse(typed);
            McLoginClient client = new McLoginClient(account(), sessionHost(),
                    serverVersion -> AutoModpackProtocol.getHandshakeVersion(serverVersion, GlobalVariables.AM_VERSION),
                    GlobalVariables.MC_VERSION);
            McLoginClient.AutoModpackData data = client.fetch(connect.host(), connect.port());

            String effectiveHost = data.address().isBlank() ? connect.host() : data.address();
            int effectivePort = data.port() == -1 ? connect.port() : data.port();
            InetSocketAddress hostAddress = AddressHelpers.format(effectiveHost, effectivePort);
            InetSocketAddress serverAddress = AddressHelpers.format(typed.host(), typed.port());
            Jsons.ModpackAddresses addresses = new Jsons.ModpackAddresses(hostAddress, serverAddress, data.requiresMagic());
            Path dir = ModpackUtils.getModpackPath(hostAddress, data.modpackName());
            PrelaunchLog.info("Obtained AutoModpack secret from %s:%d, modpack host %s:%d, dir %s",
                    connect.host(), connect.port(), effectiveHost, effectivePort, dir.getFileName());
            return new Target(dir, addresses, new Secrets.Secret(data.secret(), data.timestamp()));
        }

        private static McLoginClient.Account account() {
            Arguments arguments = FabricLoaderImpl.INSTANCE.getGameProvider().getArguments();
            String name = arguments == null ? null : arguments.get("username");
            String uuidText = arguments == null ? null : arguments.get("uuid");
            String token = arguments == null ? null : arguments.get("accessToken");
            if (name == null || name.isBlank()) {
                name = "Player";
            }
            UUID uuid = parseUuid(uuidText);
            if (uuid == null) {
                uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return new McLoginClient.Account(name, uuid, token);
        }

        private static UUID parseUuid(String text) {
            if (text == null) {
                return null;
            }
            String hex = text.replace("-", "");
            if (!hex.matches("[0-9a-fA-F]{32}")) {
                return null;
            }
            return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16));
        }

        private static String sessionHost() {
            // authlib honours this property too; keep third-party session servers working.
            String host = System.getProperty("minecraft.api.session.host", "https://sessionserver.mojang.com");
            return host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        }
    }
}

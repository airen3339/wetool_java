package org.code4everything.wetool.plugin;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.ConcurrentHashSet;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.system.OsInfo;
import cn.hutool.system.SystemUtil;
import com.alibaba.fastjson.JSON;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.code4everything.boot.base.FileUtils;
import org.code4everything.boot.config.BootConfig;
import org.code4everything.wetool.WeApplication;
import org.code4everything.wetool.constant.FileConsts;
import org.code4everything.wetool.controller.MainController;
import org.code4everything.wetool.logback.AppNameConverter;
import org.code4everything.wetool.plugin.support.WePluginSupporter;
import org.code4everything.wetool.plugin.support.config.WePluginConfig;
import org.code4everything.wetool.plugin.support.config.WePluginInfo;
import org.code4everything.wetool.plugin.support.constant.AppConsts;
import org.code4everything.wetool.plugin.support.event.EventCenter;
import org.code4everything.wetool.plugin.support.factory.BeanFactory;
import org.code4everything.wetool.plugin.support.util.FxDialogs;
import org.code4everything.wetool.plugin.support.util.FxUtils;
import org.code4everything.wetool.plugin.support.util.WeUtils;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * @author pantao
 * @since 2019/9/26
 */
@Slf4j
@UtilityClass
public final class PluginLoader {

    private static final Set<WePlugin> LOADED_PLUGINS = new ConcurrentHashSet<>();

    private static final Map<String, WePlugin> PREPARED_PLUGINS = new ConcurrentHashMap<>();

    private static final Set<String> ALREADY_ADD_TAB_NAME = new ConcurrentHashSet<>();

    public static Set<WePlugin> getLoadedPlugins() {
        return Collections.unmodifiableSet(LOADED_PLUGINS);
    }

    public static void loadPlugins() {
        // 加载工作目录下的plugins目录
        File pluginParent = new File(FileConsts.PLUGIN_FOLDER);
        if (pluginParent.exists()) {
            File[] files = pluginParent.listFiles();
            if (ArrayUtil.isNotEmpty(files)) {
                for (File file : files) {
                    preparePlugin(file, true);
                }
            }
        }

        // 加载配置文件中的插件
        Set<String> paths = WeUtils.getConfig().getPluginPaths();
        if (CollUtil.isNotEmpty(paths)) {
            paths.forEach(path -> preparePlugin(new File(path), true));
        }

        loadPluginFromPrepared();
        EventCenter.publishEvent(EventCenter.EVENT_ALL_PLUGIN_LOADED, DateUtil.date());
        addPluginForSearch("", null);

        // 保存设置文件
        WePluginConfig pluginConfig = BeanFactory.get(WePluginConfig.class);
        if (pluginConfig.isChanged()) {
            String filename = "we-plugin-config.json";
            String path = WeUtils.parsePathByOs(filename);
            if (StrUtil.isEmpty(path)) {
                path = FileUtils.currentWorkDir(filename);
            }
            FileUtil.writeUtf8String(JSON.toJSONString(pluginConfig, true), path);
        }
    }

    private static void addPluginForSearch(String prefix, Menu menu) {
        if (Objects.isNull(menu)) {
            menu = FxUtils.getPluginMenu();
        }

        ObservableList<MenuItem> menuItems = menu.getItems();
        if (CollUtil.isEmpty(menuItems)) {
            return;
        }

        menuItems.forEach(menuItem -> {
            String menuName = prefix + menuItem.getText();
            if (ALREADY_ADD_TAB_NAME.contains(menuName)) {
                return;
            }
            EventHandler<ActionEvent> eventHandler = menuItem.getOnAction();
            if (Objects.nonNull(eventHandler)) {
                ALREADY_ADD_TAB_NAME.add(menuName);
                MainController.registerAction(menuName, eventHandler);
            }
            if (menuItem instanceof Menu) {
                addPluginForSearch(menuName + "/", (Menu) menuItem);
            }
        });
    }

    public static void loadPlugins(Collection<File> plugins, final boolean checkDisable) {
        if (CollUtil.isEmpty(plugins)) {
            return;
        }
        plugins.forEach(plugin -> preparePlugin(plugin, checkDisable));
        loadPluginFromPrepared();
    }

    public static void loadPluginForTest(WePluginInfo info) {
        preparePlugin(FileUtil.file(FileUtils.currentWorkDir()), info, true);
        loadPluginFromPrepared();
    }

    private static void preparePlugin(File file, boolean checkDisable) {
        if (file.exists() && file.isFile()) {
            // 包装成 JarFile
            try (JarFile jar = new JarFile(file)) {
                // 读取插件信息
                log.info("prepare plugin: {}", file.getAbsolutePath());
                ZipEntry entry = jar.getEntry("plugin.json");
                if (Objects.isNull(entry)) {
                    log.error(StrUtil.format("plugin {} load failed: {}", file.getName(), "plugin.json not found"));
                    return;
                }
                // 解析配置到对象中
                String json = IoUtil.read(jar.getInputStream(entry), "utf-8");
                WePluginInfo info = JSON.parseObject(json, WePluginInfo.class);
                BeanFactory.get(WePluginConfig.class).putInitBootIfNotExists(info, false);
                preparePlugin(file, info, checkDisable);
            } catch (Exception e) {
                FxDialogs.showException("plugin file load failed: " + file.getName(), e);
            }
        }
    }

    private static void preparePlugin(File jarFile, WePluginInfo info, boolean checkDisable) {
        if (checkDisable && isDisabled(info)) {
            // 插件被禁止加载
            log.info("plugin {}-{}-{} disabled", info.getAuthor(), info.getName(), info.getVersion());
            return;
        }
        // 兼容性检测
        if (isIncompatible(info)) {
            return;
        }
        WePlugin plugin = new WePlugin(info, jarFile);
        // 检测插件是否已经加载
        if (LOADED_PLUGINS.contains(plugin)) {
            // 插件已被加载
            log.info("plugin {}-{} already loaded", info.getAuthor(), info.getName());
            return;
        }
        if (info.getIsolated()) {
            // 隔离的插件使用单独的类加载器
            plugin.setLoaderName(info.getName());
        }
        replaceIfNewer(plugin);
    }

    private static boolean registerPlugin(WePluginInfo info, WePluginSupporter supporter) {
        OsInfo osInfo = SystemUtil.getOsInfo();

        // @formatter:off
        boolean canRegister = (StrUtil.containsIgnoreCase(info.getSupportOs(), "windows") && osInfo.isWindows())
                || (StrUtil.containsIgnoreCase(info.getSupportOs(), "mac") && osInfo.isMac())
                || (StrUtil.containsIgnoreCase(info.getSupportOs(), "linux") && osInfo.isLinux());
        // @formatter:on
        if (!canRegister) {
            log.info("plugin {}-{}-{} not support this os", info.getAuthor(), info.getName(), info.getVersion());
            return false;
        }
        // 初始化
        if (!supporter.initialize()) {
            log.info("plugin {}-{}-{} initialize failed", info.getAuthor(), info.getName(), info.getVersion());
            return false;
        }
        // 注册托盘菜单
        java.awt.MenuItem trayMenu = supporter.registerTrayMenu();
        WeApplication.addIntoPluginMenu(trayMenu);
        // 注册主界面插件菜单
        MenuItem barMenu = supporter.registerBarMenu();
        if (ObjectUtil.isNotNull(barMenu)) {
            FxUtils.getPluginMenu().getItems().add(barMenu);
            addTabForSearch("", barMenu, info);
        }
        log.info("plugin {}-{}-{} loaded", info.getAuthor(), info.getName(), info.getVersion());
        Platform.runLater(() -> {
            if (BootConfig.isDebug()) {
                supporter.debugCall();
            }
            // 注册成功回调
            supporter.registered(info, barMenu, trayMenu);
            if (BeanFactory.get(WePluginConfig.class).putInitBootIfNotExists(info, false)) {
                supporter.initBootIfConfigured();
            }
        });
        return true;
    }

    private static void addTabForSearch(String prefix, MenuItem menuItem, WePluginInfo info) {
        String menuName = prefix + menuItem.getText();
        EventHandler<ActionEvent> action = menuItem.getOnAction();
        if (Objects.nonNull(action)) {
            ALREADY_ADD_TAB_NAME.add(menuName);
            String name = StrUtil.join("-", menuName, info.getAuthor(), info.getName());
            MainController.registerAction(name, action);
        }
        if (menuItem instanceof Menu) {
            ((Menu) menuItem).getItems().forEach(item -> addTabForSearch(menuName + "/", item, info));
        }
    }

    private static void replaceIfNewer(WePlugin plugin) {
        String key = plugin.getPluginInfo().getAuthor() + plugin.getPluginInfo().getName();
        if (PREPARED_PLUGINS.containsKey(key)) {
            WePlugin another = PREPARED_PLUGINS.get(key);
            // 当前版本是否大于预加载的版本
            if (WeUtils.isRequiredVersion(plugin.getPluginInfo().getVersion(), another.getPluginInfo().getVersion())) {
                PREPARED_PLUGINS.put(key, plugin);
                removeIfEnabled(another.getJarFile());
            } else {
                removeIfEnabled(plugin.getJarFile());
            }
        } else {
            PREPARED_PLUGINS.put(key, plugin);
        }
    }

    private static void removeIfEnabled(File file) {
        if (BooleanUtil.isTrue(WeUtils.getConfig().getAutoRemoveUnloadedPlugin())) {
            FileUtil.del(file);
        }
    }

    private static void loadPluginFromPrepared() {
        Iterator<Map.Entry<String, WePlugin>> iterator = PREPARED_PLUGINS.entrySet().iterator();
        while (iterator.hasNext()) {
            WePlugin plugin = iterator.next().getValue();
            WePluginInfo pluginInfo = plugin.getPluginInfo();
            log.info("loading plugin from prepared: {}", plugin.toJsonString());
            try {
                // 加载插件类
                plugin.getClassLoader().addJar(plugin.getJarFile());
                Class<?> clazz = plugin.getClassLoader().loadClass(pluginInfo.getSupportedClass());
                WePluginSupporter supporter = (WePluginSupporter) ReflectUtil.newInstance(clazz);
                // 添加插件菜单
                if (registerPlugin(pluginInfo, supporter)) {
                    LOADED_PLUGINS.add(plugin);
                }
                AppNameConverter.putName(supporter.getClass().getPackageName(), pluginInfo.getAuthor() + "-" + pluginInfo.getName());
            } catch (Exception e) {
                FxDialogs.showException("plugin file load failed: " + plugin.getJarFile().getName(), e);
            }
            iterator.remove();
        }
    }

    private static boolean isIncompatible(WePluginInfo info) {
        String reqVer = info.getRequireWetoolVersion();
        String errMsg = "plugin %s-%s-%s incompatible: ";
        errMsg = String.format(errMsg, info.getAuthor(), info.getName(), info.getVersion());
        // 检查plugin要求wetool依赖的wetool-plugin-support版本是否符合要求：current>=required
        if (!WeUtils.isRequiredVersion(AppConsts.CURRENT_VERSION, reqVer)) {
            log.error(errMsg + "the lower version {} of wetool is required", reqVer);
            return true;
        }
        // 检查wetool要求plugin依赖的wetool-plugin-support版本是否符合要求：required>=compatible_lower
        if (!WeUtils.isRequiredVersion(reqVer, AppConsts.COMPATIBLE_LOWER_VERSION)) {
            log.error(errMsg + "the version of plugin supporter is lower than the required: " + AppConsts.COMPATIBLE_LOWER_VERSION);
            return true;
        }
        return false;
    }

    private static boolean isDisabled(WePluginInfo pluginInfo) {
        Set<WePluginInfo> pluginDisables = WeUtils.getConfig().getPluginDisables();
        for (WePluginInfo disableInfo : pluginDisables) {
            // @formatter:off
            boolean disabled = (StrUtil.isEmpty(disableInfo.getAuthor()) || disableInfo.getAuthor().equals(pluginInfo.getAuthor()))
                    && (StrUtil.isEmpty(disableInfo.getName()) || disableInfo.getName().equals(pluginInfo.getName()))
                    && (StrUtil.isEmpty(disableInfo.getVersion()) || disableInfo.getVersion().equals(pluginInfo.getVersion()));
            // @formatter:on
            if (disabled) {
                return true;
            }
        }
        return false;
    }
}

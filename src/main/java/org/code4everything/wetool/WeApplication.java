package org.code4everything.wetool;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.system.OsInfo;
import cn.hutool.system.SystemUtil;
import com.alibaba.fastjson.JSON;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import lombok.extern.slf4j.Slf4j;
import org.code4everything.boot.base.FileUtils;
import org.code4everything.boot.base.constant.IntegerConsts;
import org.code4everything.wetool.config.WeConfig;
import org.code4everything.wetool.config.WeStart;
import org.code4everything.wetool.constant.TipConsts;
import org.code4everything.wetool.constant.TitleConsts;
import org.code4everything.wetool.constant.ViewConsts;
import org.code4everything.wetool.factory.BeanFactory;
import org.code4everything.wetool.util.FxDialogs;
import org.code4everything.wetool.util.FxUtils;
import org.code4everything.wetool.util.WeUtils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.List;
import java.util.Objects;

/**
 * @author pantao
 * @since 2018/3/30
 */
@Slf4j
public class WeApplication extends Application {

    private Stage stage;

    private boolean isTraySuccess = false;

    private static void parseConfig() {
        OsInfo osInfo = SystemUtil.getOsInfo();
        // Windows配置文件
        String winPath = FileUtils.currentWorkDir("we-config-win.json");
        // Mac配置文件
        String macPath = FileUtils.currentWorkDir("we-config-mac.json");
        // Linux配置文件
        String linPath = FileUtils.currentWorkDir("we-config-lin.json");
        // 默认配置文件
        String defPath = FileUtils.currentWorkDir("we-config.json");

        // 解析正确的配置文件路径
        String path = null;
        if (osInfo.isWindows() && FileUtil.exist(winPath)) {
            path = winPath;
        } else if (osInfo.isMac() && FileUtil.exist(macPath)) {
            path = macPath;
        } else if (osInfo.isLinux() && FileUtil.exist(linPath)) {
            path = linPath;
        } else if (FileUtil.exist(defPath)) {
            path = defPath;
        }

        if (StrUtil.isEmpty(path)) {
            log.error("wetool start error: config file not found");
            WeUtils.exitSystem();
        }
        log.info("load config file: {}", path);
        WeConfig config = JSON.parseObject(FileUtil.readUtf8String(path), WeConfig.class);
        config.setCurrentPath(path);
        BeanFactory.register(config);
        // 检测空指针
        try {
            config.requireNonNull();
        } catch (Exception e) {
            log.error("config file format error: {}", e.getMessage());
            WeUtils.exitSystem();
        }
    }

    public static void main(String[] args) {
        log.info("start wetool on os: {}", SystemUtil.getOsInfo().getName());
        parseConfig();
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        BeanFactory.register(stage);
        // 加载主界面
        VBox root = FxUtils.loadFxml(ViewConsts.MAIN);
        if (Objects.isNull(root)) {
            FxDialogs.showError(TipConsts.INIT_ERROR);
            WeUtils.exitSystem();
        }
        // 设置标题
        stage.setScene(new Scene(Objects.requireNonNull(root)));
        stage.getIcons().add(new Image(getClass().getResourceAsStream(ViewConsts.ICON)));
        stage.setTitle(TitleConsts.APP_TITLE);
        // 监听关闭事件
        stage.setOnCloseRequest((WindowEvent event) -> {
            hideStage();
            event.consume();
        });
        // 设置大小
        WeConfig config = BeanFactory.get(WeConfig.class);
        stage.setWidth(config.getInitialize().getWidth());
        stage.setHeight(config.getInitialize().getHeight());
        stage.setFullScreen(config.getInitialize().getFullscreen());

        if (SystemUtil.getOsInfo().isWindows()) {
            enableTray();
        }
        if (BeanFactory.get(WeConfig.class).getInitialize().getHide()) {
            hideStage();
        } else {
            stage.show();
        }
        log.info("wetool started");
    }

    private void hideStage() {
        if (isTraySuccess) {
            stage.hide();
        } else {
            stage.setIconified(true);
        }
    }

    private void setQuickStartMenu(Menu menu, List<WeStart> starts) {
        starts.forEach(start -> {
            if (CollUtil.isEmpty(start.getSubStarts())) {
                // 添加子菜单
                MenuItem item = new MenuItem(start.getAlias());
                item.addActionListener(e -> FxUtils.openFile(start.getLocation()));
                menu.add(item);
            } else {
                // 添加父级菜单
                Menu subMenu = new Menu(start.getAlias());
                menu.add(subMenu);
                setQuickStartMenu(subMenu, start.getSubStarts());
            }
        });
    }

    /**
     * 系统托盘
     */
    private void enableTray() {
        Platform.setImplicitExit(false);
        // 添加托盘邮件菜单
        PopupMenu popupMenu = new PopupMenu();
        // 快捷打开
        List<WeStart> starts = BeanFactory.get(WeConfig.class).getQuickStarts();
        if (CollUtil.isNotEmpty(starts)) {
            Menu menu = new Menu(TitleConsts.QUICK_START);
            setQuickStartMenu(menu, starts);
            popupMenu.add(menu);
            popupMenu.addSeparator();
        }
        // 显示
        MenuItem item = new MenuItem(TitleConsts.SHOW);
        item.addActionListener(e -> Platform.runLater(() -> stage.show()));
        popupMenu.add(item);
        // 隐藏
        item = new MenuItem(TitleConsts.HIDE);
        item.addActionListener(e -> Platform.runLater(() -> stage.hide()));
        popupMenu.add(item);
        // 重启
        popupMenu.addSeparator();
        item = new MenuItem(TitleConsts.RESTART);
        item.addActionListener(e -> FxUtils.restart());
        popupMenu.add(item);
        // 退出
        popupMenu.addSeparator();
        item = new MenuItem(TitleConsts.EXIT);
        item.addActionListener(e -> WeUtils.exitSystem());
        popupMenu.add(item);
        // 添加系统托盘图标
        try {
            SystemTray tray = SystemTray.getSystemTray();
            java.awt.Image image = ImageIO.read(getClass().getResourceAsStream(ViewConsts.ICON));
            TrayIcon trayIcon = new TrayIcon(image, TitleConsts.APP_TITLE, popupMenu);
            trayIcon.setImageAutoSize(true);
            trayIcon.setToolTip(TitleConsts.APP_TITLE);
            trayIcon.addMouseListener(new TrayMouseListener());
            tray.add(trayIcon);
            isTraySuccess = true;
        } catch (Exception e) {
            FxDialogs.showException(TipConsts.TRAY_ERROR, e);
        }
    }

    private class TrayMouseListener implements MouseListener {

        @Override
        public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == IntegerConsts.TWO) {
                // 双击图标
                Platform.runLater(() -> {
                    if (stage.isShowing()) {
                        stage.hide();
                    } else {
                        stage.show();
                    }
                });
            }
        }

        @Override
        public void mousePressed(MouseEvent e) {}

        @Override
        public void mouseReleased(MouseEvent e) {}

        @Override
        public void mouseEntered(MouseEvent e) {}

        @Override
        public void mouseExited(MouseEvent e) {}
    }
}

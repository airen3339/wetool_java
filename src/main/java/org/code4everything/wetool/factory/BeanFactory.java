package org.code4everything.wetool.factory;

import cn.hutool.core.util.StrUtil;
import lombok.experimental.UtilityClass;
import org.code4everything.wetool.controller.BaseViewController;
import org.code4everything.wetool.exception.BeanException;

import java.util.HashMap;
import java.util.Map;

/**
 * 保存打开的窗口控制器对象
 *
 * @author pantao
 * @since 2018/3/31
 */
@UtilityClass
public class BeanFactory {

    private static final Map<Class<?>, Object> CLASS_MAPPING = new HashMap<>(16);

    private static final Map<String, BaseViewController> TITLE_MAPPING = new HashMap<>(16);

    public static <T> void register(T bean) {
        CLASS_MAPPING.put(bean.getClass(), bean);
    }

    public static void registerView(String viewName, BaseViewController viewController) {
        register(viewController);
        TITLE_MAPPING.put(viewName, viewController);
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Class<T> clazz) {
        return (T) CLASS_MAPPING.get(clazz);
    }

    public static BaseViewController getView(String viewName) {
        return TITLE_MAPPING.get(viewName);
    }

    public static boolean isRegistered(Class<?> clazz) {
        return CLASS_MAPPING.containsKey(clazz);
    }

    public static <T> T safelyGet(Class<T> clazz) {
        if (isRegistered(clazz)) {
            return get(clazz);
        }
        throw new BeanException(StrUtil.format("bean '{}' not register", clazz.getName()));
    }
}
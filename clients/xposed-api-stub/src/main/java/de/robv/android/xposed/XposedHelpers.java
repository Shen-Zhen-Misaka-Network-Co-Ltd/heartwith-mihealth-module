package de.robv.android.xposed;

public final class XposedHelpers {
    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) throws ClassNotFoundException {
        return Class.forName(className, false, classLoader);
    }

    public static Object findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        return null;
    }
}

package ca.deliyannides.dirtmcp.paper.world.inspection;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

final class TestProxies {
    private TestProxies() {}

    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(
                Proxy.newProxyInstance(
                        type.getClassLoader(), new Class<?>[] {type}, objectMethods(handler)));
    }

    static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0D;
    }

    private static InvocationHandler objectMethods(InvocationHandler delegate) {
        return (proxy, method, arguments) ->
                switch (method.getName()) {
                    case "toString" ->
                            proxy.getClass().getInterfaces()[0].getSimpleName() + "Proxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> delegate.invoke(proxy, method, arguments);
                };
    }
}

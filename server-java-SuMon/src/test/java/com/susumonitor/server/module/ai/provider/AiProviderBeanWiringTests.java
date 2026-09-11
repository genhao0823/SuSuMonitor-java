package com.susumonitor.server.module.ai.provider;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 防线上事故回归：AI provider 包内组件多公共构造器时必须显式标注装配入口。 */
class AiProviderBeanWiringTests {

    /**
     * Spring 面对多个公共构造器且无 @Autowired 标注时会尝试默认构造器，
     * 启动即失败（2026-09-10 线上 SpringAiChatClient 事故）。本测试在编译期后的
     * 字节码层面拦截该类问题。
     */
    @Test
    void multiConstructorComponentsMustDeclareExplicitAutowiring() {
        assertValid(SpringAiChatClient.class);
        assertValid(OpenAiCompatibleProvider.class);
        assertValid(AiProviderResolver.class);
    }

    private void assertValid(Class<?> component) {
        Constructor<?>[] constructors = component.getConstructors();
        if (constructors.length <= 1) {
            return;
        }
        for (Constructor<?> constructor : constructors) {
            for (Annotation annotation : constructor.getAnnotations()) {
                if (annotation.annotationType() == Autowired.class
                        || annotation.annotationType().getSimpleName().equals("Autowired")) {
                    return;
                }
            }
        }
        throw new AssertionError(
                component.getSimpleName() + " 有多个公共构造器但未标注 @Autowired 装配入口");
    }
}

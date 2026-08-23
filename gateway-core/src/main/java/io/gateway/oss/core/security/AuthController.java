package io.gateway.oss.core.security;

/**
 * 历史兼容占位类。
 * <p>
 * 原有 `/auth/**` 路由已按职责拆分到多个小 Controller，避免单类承担过多接口职责。
 * 当前保留该类型仅用于兼容潜在的类型引用，不再声明任何 Spring MVC 路由。
 * </p>
 */
@Deprecated
public final class AuthController {

    private AuthController() {
        // 兼容占位类，禁止实例化。
    }
}

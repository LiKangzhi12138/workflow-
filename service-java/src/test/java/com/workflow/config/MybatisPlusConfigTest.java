package com.workflow.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MybatisPlusConfigTest {

    @Test
    void registersMysqlPaginationInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusConfig().mybatisPlusInterceptor();

        assertEquals(1, interceptor.getInterceptors().size());
        assertInstanceOf(PaginationInnerInterceptor.class, interceptor.getInterceptors().getFirst());
    }
}

package com.sharer.server.config;

import com.sharer.server.core.utils.SpringContextUtil;
import com.sharer.server.core.zk.CuratorZKclient;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class ZkClientConfig implements ApplicationContextAware {

    @Value("${zookeeper.connect.url}")
    private String zkConnect;

    @Value("${zookeeper.connect.sessionTimeout}")
    private String zkSessionTimeout;


    /**
     * @see BeanInitializationException
     */
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        SpringContextUtil.setContext(applicationContext);

    }


    @Bean(name = "curatorZKClient", destroyMethod = "destroy")
    public CuratorZKclient curatorZKClient() {
        return new CuratorZKclient(zkConnect, zkSessionTimeout);
    }


}
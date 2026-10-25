package com.college.staffcoord.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
class DataSourceConfig {
    @Bean @Primary
    DataSource dataSource(DataSourceProperties props) {
        HikariDataSource pool = props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        pool.setMaximumPoolSize(10);
        return new RlsDataSource(pool);
    }
}

package com.sparrowlogic.grafanamcp;

import com.sparrowlogic.grafanamcp.client.GrafanaClient;
import com.sparrowlogic.grafanamcp.tools.AnnotationTools;
import com.sparrowlogic.grafanamcp.tools.DashboardTools;
import com.sparrowlogic.grafanamcp.tools.MetricsTools;
import com.sparrowlogic.grafanamcp.tools.NodeMetricsTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GrafanaMcpApplicationTests {

    @MockitoBean
    RestClient grafanaRestClient;

    @Autowired
    ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    void toolBeansRegistered() {
        assertThat(context.getBean(DashboardTools.class)).isNotNull();
        assertThat(context.getBean(AnnotationTools.class)).isNotNull();
        assertThat(context.getBean(MetricsTools.class)).isNotNull();
        assertThat(context.getBean(NodeMetricsTools.class)).isNotNull();
    }

    @Test
    void clientBeanRegistered() {
        assertThat(context.getBean(GrafanaClient.class)).isNotNull();
    }
}

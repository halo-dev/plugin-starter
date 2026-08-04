package run.halo.traffic.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

@Slf4j
@Component
public class TrafficMonitorPlugin extends BasePlugin {

    public TrafficMonitorPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        log.info("IP traffic monitor plugin started");
    }

    @Override
    public void stop() {
        log.info("IP traffic monitor plugin stopped");
    }
}

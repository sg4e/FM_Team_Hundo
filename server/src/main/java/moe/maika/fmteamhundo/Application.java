package moe.maika.fmteamhundo;

import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.theme.lumo.Lumo;

import moe.maika.fmteamhundo.service.TwitchVodProperties;
import moe.maika.fmteamhundo.state.HundoConstants;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@CssImport("./themes/default/styles.css")
@StyleSheet(Lumo.STYLESHEET)
@StyleSheet(Lumo.UTILITY_STYLESHEET)
@Push
@JsModule("./themes/default/theme-toggle.js")
@JsModule("./themes/default/card-popover.js")
@EnableScheduling
@EnableConfigurationProperties({ HundoConstants.class, TwitchVodProperties.class })
public class Application implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}

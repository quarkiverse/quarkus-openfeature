package io.quarkiverse.openfeature.deployment.devui;

import java.util.ArrayList;
import java.util.List;

import io.quarkiverse.openfeature.runtime.OpenFeatureBuildTimeConfig;
import io.quarkiverse.openfeature.runtime.devui.OpenFeatureJsonRpcService;
import io.quarkus.deployment.IsLocalDevelopment;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.devui.spi.JsonRPCProvidersBuildItem;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;

@BuildSteps(onlyIf = IsLocalDevelopment.class)
class OpenFeatureDevUiProcessor {
    @BuildStep
    CardPageBuildItem create(OpenFeatureBuildTimeConfig config) {
        CardPageBuildItem pageBuildItem = new CardPageBuildItem();

        pageBuildItem.setLogo("openfeature_dark.svg", "openfeature_light.svg");

        pageBuildItem.addLibraryVersion("dev.openfeature", "sdk", "OpenFeature",
                "https://openfeature.dev/");

        pageBuildItem.addPage(Page.webComponentPageBuilder()
                .title("Feature Flags")
                .componentLink("qwc-openfeature-flags.js")
                .icon("font-awesome-solid:flag"));

        List<String> namedDomains = new ArrayList<>();
        for (String domain : config.domains().keySet()) {
            if (!OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN.equals(domain)) {
                namedDomains.add(domain);
            }
        }
        namedDomains.sort(null);
        List<String> domains = new ArrayList<>();
        domains.add(OpenFeatureBuildTimeConfig.DEFAULT_DOMAIN);
        domains.addAll(namedDomains);
        pageBuildItem.addBuildTimeData("domains", domains);

        return pageBuildItem;
    }

    @BuildStep
    JsonRPCProvidersBuildItem createJsonRpcService() {
        return new JsonRPCProvidersBuildItem(OpenFeatureJsonRpcService.class);
    }
}

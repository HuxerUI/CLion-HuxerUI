package org.huxerui.clion.run;

import org.huxerui.clion.PlatformNames;

import java.util.List;

public record HuxerUIDevice(String platform, String id, String name, String state, List<String> abis) {
    public HuxerUIDevice(String platform, String id, String name, String state) {
        this(platform, id, name, state, List.of());
    }

    public boolean IsReady() {
        return state.equals("ready");
    }

    public String DisplayName() {
        String platform_name = PlatformNames.DisplayName(platform);
        return name.isBlank() ? platform_name + " — " + id : platform_name + " — " + name;
    }
}

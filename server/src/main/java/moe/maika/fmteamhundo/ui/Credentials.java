package moe.maika.fmteamhundo.ui;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Credentials {
    private String key;
    private String url;
    private String username;
}
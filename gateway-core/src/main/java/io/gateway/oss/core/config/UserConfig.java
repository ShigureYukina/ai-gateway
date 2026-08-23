package io.gateway.oss.core.config;


public class UserConfig {

    private String password = "";
    private String clientId;
    private String role = "user";

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        if (role == null || role.isBlank()) {
            this.role = "user";
            return;
        }
        this.role = role;
    }
}

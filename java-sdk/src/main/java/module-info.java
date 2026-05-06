module com.anthropic.artifactmgmt.client {
    requires java.net.http;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;
    requires static jakarta.annotation;

    exports com.anthropic.artifactmgmt.client;
    exports com.anthropic.artifactmgmt.client.api;
    exports com.anthropic.artifactmgmt.client.model;
}

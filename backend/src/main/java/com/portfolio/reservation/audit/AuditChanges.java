package com.portfolio.reservation.audit;

import java.util.*;
import tools.jackson.databind.JsonNode;

/** Closed whitelist on writing AND reading; never serialize arbitrary historical JSON. */
public final class AuditChanges {
    private AuditChanges() {}
    public static Map<String,Object> sanitize(JsonNode value) {
        var safe=new LinkedHashMap<String,Object>();
        for(String key:List.of("oldRole","newRole")) {
            var node=value.path(key);
            if(node.isString() && Set.of("CLIENTE","EMPLEADO","ADMIN").contains(node.asText())) safe.put(key,node.asText());
        }
        for(String key:List.of("oldActive","newActive")) if(value.path(key).isBoolean()) safe.put(key,value.path(key).asBoolean());
        return safe;
    }
}

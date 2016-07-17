package com.ctrip.zeus.logstats;

import com.netflix.config.DynamicPropertyFactory;
import com.netflix.config.DynamicStringProperty;

/**
 * Created by mengyizhou on 6/19/16.
 */
public class LogFormat {
    private static DynamicStringProperty logFormat = DynamicPropertyFactory.getInstance().getStringProperty("slb.nginx.log-format",
            "'[$time_local] $host $hostname $server_addr $request_method $request_uri '\n" +
                    "'$server_port $remote_user $remote_addr $http_x_forwarded_for '\n" +
                    "'$server_protocol \"$http_user_agent\" \"$http_cookie\" \"$http_referer\" '\n" +
                    "'$status $request_length $bytes_sent $request_time $upstream_response_time '\n" +
                    "'$upstream_addr $upstream_status $proxy_host'"
    );

    public static String getMain() {
        return logFormat.get();
    }

    public static String getMainCompactString() {
        return logFormat.get().replaceAll("'", "").replaceAll("\\n", "");
    }
}

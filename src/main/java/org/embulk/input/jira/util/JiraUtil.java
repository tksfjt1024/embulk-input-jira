package org.embulk.input.jira.util;

import com.google.gson.JsonElement;

import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.embulk.config.ConfigException;
import org.embulk.input.jira.Issue;
import org.embulk.input.jira.JiraInputPlugin.PluginTask;
import org.embulk.spi.Column;
import org.embulk.spi.ColumnConfig;
import org.embulk.spi.ColumnVisitor;
import org.embulk.spi.PageBuilder;
import org.embulk.spi.Schema;
import org.embulk.spi.json.JsonParser;
import org.embulk.spi.time.Timestamp;
import org.embulk.spi.time.TimestampParser;

import javax.ws.rs.core.UriBuilder;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.google.common.base.Strings.isNullOrEmpty;
import static org.embulk.input.jira.Constant.CREDENTIAL_URI_PATH;
import static org.embulk.input.jira.Constant.DEFAULT_TIMESTAMP_PATTERN;
import static org.embulk.input.jira.Constant.HTTP_TIMEOUT;
import static org.embulk.input.jira.Constant.SEARCH_URI_PATH;

public final class JiraUtil
{
    private JiraUtil() {}

    public static int calculateTotalPage(int totalCount, int resultPerPage)
    {
        return (int) Math.ceil((double) totalCount / resultPerPage);
    }

    public static String buildPermissionUrl(String url)
    {
        return UriBuilder.fromUri(url).path(CREDENTIAL_URI_PATH).build().toString();
    }

    public static String buildSearchUrl(String url)
    {
        return UriBuilder.fromUri(url).path(SEARCH_URI_PATH).build().toString();
    }

    public static void validateTaskConfig(final PluginTask task)
    {
        String username = task.getUsername();
        if (isNullOrEmpty(username)) {
            throw new ConfigException("Username or email could not be empty");
        }
        String password = task.getPassword();
        if (isNullOrEmpty(password)) {
            throw new ConfigException("Password could not be empty");
        }
        String uri = task.getUri();
        if (isNullOrEmpty(uri)) {
            throw new ConfigException("JIRA API endpoint could not be empty");
        }
        try (CloseableHttpClient client = HttpClientBuilder.create()
                                            .setDefaultRequestConfig(RequestConfig.custom()
                                                                                .setConnectTimeout(HTTP_TIMEOUT)
                                                                                .setConnectionRequestTimeout(HTTP_TIMEOUT)
                                                                                .setSocketTimeout(HTTP_TIMEOUT)
                                                                                .setCookieSpec(CookieSpecs.STANDARD)
                                                                                .build())
                                            .build()) {
            HttpGet request = new HttpGet(uri);
            try (CloseableHttpResponse response = client.execute(request)) {
                response.getStatusLine().getStatusCode();
            }
        }
        catch (IOException | IllegalArgumentException e) {
            throw new ConfigException("JIRA API endpoint is incorrect or not available");
        }
        int retryInitialWaitSec = task.getInitialRetryIntervalMillis();
        if (retryInitialWaitSec < 1) {
            throw new ConfigException("Initial retry delay should be equal or greater than 1");
        }
        int retryLimit = task.getRetryLimit();
        if (retryLimit < 0 || retryLimit > 10) {
            throw new ConfigException("Retry limit should between 0 and 10");
        }
    }

    /*
     * For getting the timestamp value of the node
     * Sometime if the parser could not parse the value then return null
     * */
    private static Timestamp getTimestampValue(PluginTask task, Column column, String value)
    {
        List<ColumnConfig> columnConfigs = task.getColumns().getColumns();
        String pattern = DEFAULT_TIMESTAMP_PATTERN;
        for (ColumnConfig config : columnConfigs) {
            if (config.getName().equals(column.getName())
                    && config.getConfigSource() != null
                    && config.getConfigSource().getObjectNode() != null
                    && config.getConfigSource().getObjectNode().get("format") != null
                    && config.getConfigSource().getObjectNode().get("format").isTextual()) {
                pattern = config.getConfigSource().getObjectNode().get("format").asText();
                break;
            }
        }
        TimestampParser parser = TimestampParser.of(pattern, "UTC");
        Timestamp result = null;
        try {
            result = parser.parse(value);
        }
        catch (Exception e) {
        }
        return result;
    }

    /*
     * For getting the Long value of the node
     * Sometime if error occurs (i.e a JSON value but user modified it as long) then return null
     * */
    private static Long getLongValue(JsonElement value)
    {
        Long result = null;
        try {
            result = value.getAsLong();
        }
        catch (Exception e) {
        }
        return result;
    }

    /*
     * For getting the Double value of the node
     * Sometime if error occurs (i.e a JSON value but user modified it as double) then return null
     * */
    private static Double getDoubleValue(JsonElement value)
    {
        Double result = null;
        try {
            result = value.getAsDouble();
        }
        catch (Exception e) {
        }
        return result;
    }

    /*
     * For getting the Boolean value of the node
     * Sometime if error occurs (i.e a JSON value but user modified it as boolean) then return null
     * */
    private static Boolean getBooleanValue(JsonElement value)
    {
        Boolean result = null;
        try {
            result = value.getAsBoolean();
        }
        catch (Exception e) {
        }
        return result;
    }

    public static void addRecord(Issue issue, Schema schema, PluginTask task, PageBuilder pageBuilder)
    {
        schema.visitColumns(new ColumnVisitor() {
            @Override
            public void jsonColumn(Column column)
            {
                JsonElement data = issue.getValue(column.getName());
                if (data.isJsonNull() || data.isJsonPrimitive()) {
                    pageBuilder.setNull(column);
                }
                else {
                    pageBuilder.setJson(column, new JsonParser().parse(data.toString()));
                }
            }

            @Override
            public void stringColumn(Column column)
            {
                JsonElement data = issue.getValue(column.getName());
                if (data.isJsonNull()) {
                    pageBuilder.setNull(column);
                }
                else if (data.isJsonPrimitive()) {
                    pageBuilder.setString(column, data.getAsString());
                }
                else if (data.isJsonArray()) {
                    pageBuilder.setString(column, StreamSupport.stream(data.getAsJsonArray().spliterator(), false)
                            .map(obj -> {
                                if (obj.isJsonPrimitive()) {
                                    return obj.getAsString();
                                }
                                else {
                                    return obj.toString();
                                }
                            })
                            .collect(Collectors.joining(",")));
                }
                else {
                    pageBuilder.setString(column, data.toString());
                }
            }

            @Override
            public void timestampColumn(Column column)
            {
                JsonElement data = issue.getValue(column.getName());
                if (data.isJsonNull() || data.isJsonObject() || data.isJsonArray()) {
                    pageBuilder.setNull(column);
                }
                else {
                    Timestamp value = getTimestampValue(task, column, data.getAsString());
                    if (value == null) {
                        pageBuilder.setNull(column);
                    }
                    else {
                        pageBuilder.setTimestamp(column, value);
                    }
                }
            }

            @Override
            public void booleanColumn(Column column)
            {
                Boolean value = getBooleanValue(issue.getValue(column.getName()));
                if (value == null) {
                    pageBuilder.setNull(column);
                }
                else {
                    pageBuilder.setBoolean(column, value);
                }
            }

            @Override
            public void longColumn(Column column)
            {
                Long value = getLongValue(issue.getValue(column.getName()));
                if (value == null) {
                    pageBuilder.setNull(column);
                }
                else {
                    pageBuilder.setLong(column, value);
                }
            }

            @Override
            public void doubleColumn(Column column)
            {
                Double value = getDoubleValue(issue.getValue(column.getName()));
                if (value == null) {
                    pageBuilder.setNull(column);
                }
                else {
                    pageBuilder.setDouble(column, value);
                }
            }
        });
        pageBuilder.addRecord();
    }
}
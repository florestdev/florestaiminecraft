package ru.florestdev.florestGigaChat;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.NoSuchAlgorithmException;
import java.security.KeyManagementException;
import java.security.cert.X509Certificate;

final class GigaChatOkClient {

    private final String apiKey;
    private final HttpClient httpClient;
    private final String unused;
    private final Plugin plugin;

    /* ---------- Конструктор ---------- */
    public GigaChatOkClient(String apiKey, String unused, boolean insecureSSL, Plugin plugin) {
        this.apiKey = apiKey;
        this.unused = unused;
        this.plugin = plugin;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (insecureSSL) {
            try {
                TrustManager[] trustAll = new TrustManager[] {
                        new X509TrustManager() {
                            public X509Certificate[] getAcceptedIssuers() { return null; }
                            public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                            public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                        }
                };
                SSLContext sslContext = SSLContext.getInstance("SSL");
                sslContext.init(null, trustAll, new java.security.SecureRandom());
                builder.sslContext(sslContext);
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                throw new RuntimeException("Failed to create insecure SSL context", e);
            }
        }
        this.httpClient = builder.build();
    }

    public String ask(String request) throws Exception {
        Gson gson = new Gson();
        // Формируем JSON-запрос для SambaNova API
        JsonObject jsonRequest = new JsonObject();
        jsonRequest.addProperty("model", unused);
        jsonRequest.addProperty("stream", true);
        jsonRequest.addProperty("max_tokens", plugin.getConfig().getInt("max_tokens"));
        JsonArray messages = new JsonArray();
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", plugin.getConfig().getString("system_message"));
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", request);
        messages.add(userMessage);
        messages.add(systemMessage);
        jsonRequest.add("messages", messages);

        // Создаем HTTP-запрос
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.sambanova.ai/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(jsonRequest)))
                .build();

        // Отправляем запрос и получаем ответ как InputStream для стриминга
        HttpResponse<java.io.InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());

        // Проверяем статус ответа
        if (response.statusCode() != 200) {
            // Для чтения тела ошибки
            String errorBody = new String(response.body().readAllBytes());
            throw new RuntimeException("SambaNova API error: " + response.statusCode() + " - " + errorBody);
        }

        // Обрабатываем стриминговый ответ
        StringBuilder fullResponse = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    try {
                        JsonObject chunk = gson.fromJson(data, JsonObject.class);
                        JsonArray choices = chunk.getAsJsonArray("choices");
                        if (choices != null && !choices.isEmpty()) {
                            JsonObject choice = choices.get(0).getAsJsonObject();
                            JsonObject delta = choice.getAsJsonObject("delta");
                            if (delta != null && delta.has("content")) {
                                fullResponse.append(delta.get("content").getAsString());
                            }
                        }
                    } catch (Exception e) {
                        // Игнорируем некорректные чанки
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading streaming response", e);
        }

        if (fullResponse.isEmpty()) {
            throw new RuntimeException("No content found in SambaNova API response");
        }
        return fullResponse.toString();
    }
}

public class FlorestAIProcesser implements CommandExecutor {

    public final Plugin plugin;

    public FlorestAIProcesser(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String s, String[] strings) {
        if (strings.length == 0) {
            commandSender.sendMessage("Использование: /florestai <request/reload> <запрос>");
            return false;
        } else {
            if (strings[0].equalsIgnoreCase("reload")) {
                if (!commandSender.hasPermission("florestgigachat.admin")) {
                    commandSender.sendMessage("Ты должен иметь florestgigachat.admin для перезагрузки конфига плагина.");
                } else {
                    commandSender.sendMessage("Конфигурация перезагружена, брат!");
                    plugin.reloadConfig();
                }
            } else if (strings[0].equalsIgnoreCase("request")) {
                if (strings.length < 2) {
                    commandSender.sendMessage("Вы должны указать ваш запрос!");
                } else {
                    // Объединяем аргументы, начиная со второго (игнорируем "request")
                    String request = String.join(" ", Arrays.copyOfRange(strings, 1, strings.length));
                    String apiKey = plugin.getConfig().getString("api_key");
                    if (apiKey == null || apiKey.isEmpty()) {
                        commandSender.sendMessage("Ошибка: api_key не указан в конфиге!");
                        return false;
                    }
                    String result;
                    try {
                        String model = plugin.getConfig().getString("model");
                        result = new GigaChatOkClient(apiKey, model, true, plugin).ask(request);
                    } catch (Exception e) {
                        commandSender.sendMessage(plugin.getConfig().getString("format_of_errors").replace("{message}", e.getMessage()));
                        return false;
                    }
                    commandSender.sendMessage(plugin.getConfig().getString("format").replace("{message}", result));
                    try {
                        int delay = plugin.getConfig().getInt("delay_between_commands");
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        commandSender.sendMessage("Ошибка задержки: " + e.getMessage());
                    }
                }
            } else {
                commandSender.sendMessage("Usage команды: /florestai <request/reload> <запрос>");
            }
        }
        return true;
    }
}

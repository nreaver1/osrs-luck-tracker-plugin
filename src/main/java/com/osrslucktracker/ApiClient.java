package com.osrslucktracker;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

@Slf4j
@Singleton
class ApiClient
{
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final LuckTrackerConfig config;

    @Inject
    ApiClient(OkHttpClient httpClient, Gson gson, LuckTrackerConfig config)
    {
        this.httpClient = httpClient;
        this.gson = gson;
        this.config = config;
    }

    /**
     * Mints a token for a new account, or, when {@code installToken} is
     * the account's current token, updates its IGN and returns the same
     * token. Without a valid token an existing account gets a 409.
     */
    void register(String accountHash, String ign, String installToken, Consumer<String> onToken)
    {
        if (config.apiBaseUrl().isEmpty())
        {
            log.debug("API base URL not configured, skipping register");
            return;
        }

        RegisterRequest body = new RegisterRequest(accountHash, ign, installToken);
        Request request = new Request.Builder()
            .url(config.apiBaseUrl() + "/register")
            .header("apikey", config.publishableKey())
            .post(RequestBody.create(JSON, gson.toJson(body)))
            .build();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.warn("Register call failed", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try (Response r = response)
                {
                    if (r.code() == 409)
                    {
                        // The server only hands out a token when it creates the
                        // account, so a lost local token can't be recovered here.
                        log.warn("This account is already registered and the stored install token (if any) was not accepted; drops will not be submitted");
                        return;
                    }
                    if (!r.isSuccessful() || r.body() == null)
                    {
                        log.warn("Register call returned HTTP {}", r.code());
                        return;
                    }
                    RegisterResponse parsed = gson.fromJson(r.body().string(), RegisterResponse.class);
                    if (parsed != null && parsed.installToken != null)
                    {
                        onToken.accept(parsed.installToken);
                    }
                }
                catch (Exception e)
                {
                    log.warn("Failed to parse register response", e);
                }
            }
        });
    }

    void ingestDrop(String installToken, String accountHash, int itemId, String sourceName, int kcReceived, int currentKc)
    {
        if (config.apiBaseUrl().isEmpty())
        {
            log.debug("API base URL not configured, skipping ingest-drop");
            return;
        }

        IngestDropRequest body = new IngestDropRequest(installToken, accountHash, itemId, sourceName, kcReceived, currentKc);
        Request request = new Request.Builder()
            .url(config.apiBaseUrl() + "/ingest-drop")
            .header("apikey", config.publishableKey())
            .post(RequestBody.create(JSON, gson.toJson(body)))
            .build();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.warn("Ingest-drop call failed for item {}", itemId, e);
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (Response r = response)
                {
                    if (r.isSuccessful())
                    {
                        log.info("Logged item {} from {} at {} kc", itemId, sourceName, kcReceived);
                    }
                    else
                    {
                        log.warn("Ingest-drop returned HTTP {} for item {}", r.code(), itemId);
                    }
                }
            }
        });
    }

    void fetchCatalog(Consumer<List<CatalogEntry>> onResult)
    {
        if (config.apiBaseUrl().isEmpty())
        {
            log.debug("API base URL not configured, skipping catalog fetch");
            return;
        }

        Request request = new Request.Builder()
            .url(config.apiBaseUrl() + "/drop-rates-catalog")
            .header("apikey", config.publishableKey())
            .get()
            .build();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.warn("Catalog fetch failed", e);
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (Response r = response)
                {
                    if (!r.isSuccessful() || r.body() == null)
                    {
                        log.warn("Catalog fetch returned HTTP {}", r.code());
                        return;
                    }
                    CatalogResponse parsed = gson.fromJson(r.body().string(), CatalogResponse.class);
                    if (parsed != null && parsed.entries != null)
                    {
                        onResult.accept(parsed.entries);
                    }
                }
                catch (Exception e)
                {
                    log.warn("Failed to parse catalog response", e);
                }
            }
        });
    }

    void backfillDrop(String installToken, String accountHash, int itemId, String sourceName, Consumer<Boolean> onResult)
    {
        if (config.apiBaseUrl().isEmpty())
        {
            log.debug("API base URL not configured, skipping backfill-drop");
            return;
        }

        BackfillDropRequest body = new BackfillDropRequest(installToken, accountHash, itemId, sourceName);
        Request request = new Request.Builder()
            .url(config.apiBaseUrl() + "/backfill-drop")
            .header("apikey", config.publishableKey())
            .post(RequestBody.create(JSON, gson.toJson(body)))
            .build();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.warn("Backfill-drop call failed for item {}", itemId, e);
                onResult.accept(false);
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (Response r = response)
                {
                    boolean success = r.isSuccessful();
                    if (!success)
                    {
                        log.warn("Backfill-drop returned HTTP {} for item {}", r.code(), itemId);
                    }
                    onResult.accept(success);
                }
            }
        });
    }

    /**
     * Every (item_id, source_name) this player already has a row for, as
     * {@link BackfillPlanner.Candidate#key()}s, read from the public
     * /get-player-luck endpoint. A player with no rows yet (404) gets an
     * empty set; any other failure calls back with null.
     */
    void fetchRecordedDrops(String ign, Consumer<Set<String>> onResult)
    {
        if (config.apiBaseUrl().isEmpty())
        {
            onResult.accept(null);
            return;
        }

        HttpUrl url = HttpUrl.parse(config.apiBaseUrl() + "/get-player-luck");
        if (url == null)
        {
            onResult.accept(null);
            return;
        }

        Request request = new Request.Builder()
            .url(url.newBuilder().addQueryParameter("ign", ign).build())
            .header("apikey", config.publishableKey())
            .get()
            .build();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.warn("Recorded-drops fetch failed", e);
                onResult.accept(null);
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (Response r = response)
                {
                    if (r.code() == 404)
                    {
                        onResult.accept(new HashSet<>());
                        return;
                    }
                    if (!r.isSuccessful() || r.body() == null)
                    {
                        log.warn("Recorded-drops fetch returned HTTP {}", r.code());
                        onResult.accept(null);
                        return;
                    }
                    PlayerLuckResponse parsed = gson.fromJson(r.body().string(), PlayerLuckResponse.class);
                    Set<String> keys = new HashSet<>();
                    if (parsed != null && parsed.results != null)
                    {
                        for (PlayerLuckResponse.Result result : parsed.results)
                        {
                            keys.add(new BackfillPlanner.Candidate(result.itemId, result.sourceName).key());
                        }
                    }
                    onResult.accept(keys);
                }
                catch (Exception e)
                {
                    log.warn("Failed to parse recorded-drops response", e);
                    onResult.accept(null);
                }
            }
        });
    }

    /**
     * Batch form of {@link #backfillDrop}. Calls back with null on any
     * failure (network, auth, rate limit), otherwise the server's counts.
     */
    void backfillDrops(String installToken, String accountHash, List<BackfillBatchRequest.Drop> drops,
        Consumer<BackfillBatchResponse> onResult)
    {
        if (config.apiBaseUrl().isEmpty())
        {
            log.debug("API base URL not configured, skipping backfill-drop batch");
            onResult.accept(null);
            return;
        }

        BackfillBatchRequest body = new BackfillBatchRequest(installToken, accountHash, drops);
        Request request = new Request.Builder()
            .url(config.apiBaseUrl() + "/backfill-drop")
            .header("apikey", config.publishableKey())
            .post(RequestBody.create(JSON, gson.toJson(body)))
            .build();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.warn("Backfill-drop batch call failed ({} items)", drops.size(), e);
                onResult.accept(null);
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (Response r = response)
                {
                    if (!r.isSuccessful() || r.body() == null)
                    {
                        log.warn("Backfill-drop batch returned HTTP {} ({} items)", r.code(), drops.size());
                        onResult.accept(null);
                        return;
                    }
                    onResult.accept(gson.fromJson(r.body().string(), BackfillBatchResponse.class));
                }
                catch (Exception e)
                {
                    log.warn("Failed to parse backfill-drop batch response", e);
                    onResult.accept(null);
                }
            }
        });
    }
}

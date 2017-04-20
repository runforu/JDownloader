package com.opera.traffic;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.util.Vector;

import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.config.RequestConfig.Builder;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;

public class HttpClientHelper implements Runnable {

    public interface Listener {
        public static enum Error {
            BadUri, ConnTimeOut, HttpError, SocketTimeout, UnknowError, URISyntaxError;
        }

        public void onError(HttpClientHelper client, Error e);

        public void onHttpSuccess(HttpClientHelper client, HttpResponse response, String host);
    }

    private static Vector<CloseableHttpClient> mHttpClients = new Vector<CloseableHttpClient>();

    private final Listener mListener;
    private final String mUri;
    private int mTries;

    public static void clean() {
        for (CloseableHttpClient client : mHttpClients) {
            try {
                client.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public HttpClientHelper(String uri, Listener l) {
        mListener = l;
        mUri = uri;
        mTries = 0;
    }

    public String getUri() {
        return mUri;
    }

    public int getTries() {
        return mTries;
    }

    @Override
    public void run() {
        if (mUri == null) {
            return;
        }
        mTries++;
        CloseableHttpClient httpClient = null;
        if (!mHttpClients.isEmpty()) {
            httpClient = mHttpClients.remove(0);
        } else {
            httpClient = HttpClients.createDefault();
        }

        HttpResponse response = null;
        HttpGet httpRequest = null;
        try {
            httpRequest = new HttpGet(mUri);
            response = request(httpClient, httpRequest, false);
            if (response != null && response.getStatusLine().getStatusCode() == 200) {
                mListener.onHttpSuccess(this, response, httpRequest.getURI().getHost());
            } else {
                mListener.onError(this, Listener.Error.HttpError);
            }
        } catch (IllegalArgumentException e) {
            mListener.onError(this, Listener.Error.BadUri);
            e.printStackTrace();
        } catch (ConnectTimeoutException e) {
            mListener.onError(this, Listener.Error.ConnTimeOut);
            e.printStackTrace();
        } catch (SocketTimeoutException e) {
            mListener.onError(this, Listener.Error.SocketTimeout);
            e.printStackTrace();
        } catch (IOException e) {
            mListener.onError(this, Listener.Error.UnknowError);
            e.printStackTrace();
        } catch (URISyntaxException e) {
            mListener.onError(this, Listener.Error.URISyntaxError);
            e.printStackTrace();
        } catch (Exception e) {
            mListener.onError(this, Listener.Error.UnknowError);
            e.printStackTrace();
        } finally {
            if (httpRequest != null) {
                httpRequest.abort();
            }
            consumeResponse(response);
            mHttpClients.add(httpClient);
        }
    }

    private void consumeResponse(HttpResponse response) {
        if (response != null && response.getEntity() != null) {
            try {
                EntityUtils.consume(response.getEntity());
            } catch (Exception e2) {
            }
        }
    }

    private HttpResponse request(CloseableHttpClient CloseableHttpClient, HttpGet httpRequest, boolean keepAlive)
            throws IOException, URISyntaxException {
        Builder requestConfigBuilder = RequestConfig.custom();
        requestConfigBuilder.setConnectionRequestTimeout(10000).setSocketTimeout(10000).setRedirectsEnabled(true)
                .setMaxRedirects(1);
        httpRequest.setConfig(requestConfigBuilder.build());

        httpRequest.setHeader("Accept-Charset", "utf-8");
        httpRequest.setHeader(HTTP.CONN_DIRECTIVE, keepAlive ? HTTP.CONN_KEEP_ALIVE : HTTP.CONN_CLOSE);
        httpRequest.setHeader(HttpHeaders.ACCEPT_ENCODING, "gzip");

        return CloseableHttpClient.execute(httpRequest);
    }
}

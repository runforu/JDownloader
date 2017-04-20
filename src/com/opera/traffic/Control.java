package com.opera.traffic;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.htmlparser.Node;
import org.htmlparser.Parser;
import org.htmlparser.Tag;
import org.htmlparser.tags.LinkTag;
import org.htmlparser.util.ParserException;
import org.htmlparser.visitors.NodeVisitor;

public class Control implements HttpClientHelper.Listener {
    public interface DownloadFilter {
        public boolean isDesired(Header[] headers);
    }

    public interface LinkFilter {
        public boolean isDesired(LinkTag lt);
    }

    public interface RoundCompleted {
        public void roundCompleted();
    }

    private final int MAX_LOADING_THREAD = 32;
    private final ExecutorService mCachedExecutor = Executors.newCachedThreadPool();
    private final List<LinkFilter> mLinkFilters = new ArrayList<LinkFilter>();
    private final List<DownloadFilter> mDownloadFilters = new ArrayList<DownloadFilter>();
    private final Map<HttpClientHelper, Future<?>> mFutures = new HashMap<HttpClientHelper, Future<?>>();
    private String mPath = ".";
    private final List<LinkTag> mPendingLinks = new ArrayList<LinkTag>();
    private final List<HttpClientHelper> mFailedDownloadClients = new ArrayList<HttpClientHelper>();
    private final RoundCompleted mRoundCompleted;
    private final Map<String, LinkTag> mVisitedLinks = new HashMap<String, LinkTag>();

    public Control(RoundCompleted mRoundCompleted) {
        super();
        this.mRoundCompleted = mRoundCompleted;
    }

    public void addFilter(LinkFilter filter) {
        mLinkFilters.clear();
        mLinkFilters.add(filter);
    }

    public void addDownloadFilter(DownloadFilter filter) {
        mDownloadFilters.clear();
        mDownloadFilters.add(filter);
    }

    // set link
    public synchronized void addLink(LinkTag lt) {
        if (mVisitedLinks.containsKey(lt.getLink())) {
            return;
        }
        for (LinkFilter lf : mLinkFilters) {
            lf.isDesired(lt);
        }
        mVisitedLinks.put(lt.getLink(), lt);
    }

    public void clearLinkFilters() {
        mLinkFilters.clear();
    }

    public void clearDownloadFilters() {
        mDownloadFilters.clear();
    }

    public synchronized void go() {
        mPendingLinks.clear();
        for (LinkTag lt : mVisitedLinks.values()) {
            if (mLinkFilters.isEmpty()) {
                mPendingLinks.add(lt);
            } else {
                for (LinkFilter lf : mLinkFilters) {
                    if (lf.isDesired(lt)) {
                        mPendingLinks.add(lt);
                    }
                }
            }
        }
        mVisitedLinks.clear();
        for (LinkTag lt : mPendingLinks) {
            mVisitedLinks.put(lt.getLink(), lt);
        }

        tryInvoke();
    }

    public List<LinkTag> getLinks() {
        List<LinkTag> list = new ArrayList<>();
        for (LinkTag lt : mVisitedLinks.values()) {
            list.add(lt);
        }
        return list;
    }

    public List<LinkTag> getDesiredLinks() {
        List<LinkTag> list = new ArrayList<>();
        for (LinkTag lt : mVisitedLinks.values()) {
            if (mLinkFilters.isEmpty()) {
                list.add(lt);
            } else {
                for (LinkFilter lf : mLinkFilters) {
                    if (lf.isDesired(lt)) {
                        list.add(lt);
                    }
                }
            }
        }
        return list;
    }

    @Override
    public void onError(HttpClientHelper client, HttpClientHelper.Listener.Error e) {
        switch (e) {
        case ConnTimeOut:
            break;
        case SocketTimeout:
            break;
        case HttpError:
            break;
        case UnknowError:
            break;
        case URISyntaxError:
            break;
        default:
            break;
        }
        oneShortComplete(client);
    }

    @Override
    public void onHttpSuccess(HttpClientHelper client, HttpResponse response, String host) {
        if (isHtmlResponse(response.getHeaders("Content-type"))) {
            InputStream stream = null;
            try {
                stream = response.getEntity().getContent();
                String html = getHtml(response, stream);
                if (html != null) {
                    visitHtml(html, "utf-8", host);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try {
                    stream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } else if (isDownloadable(response.getHeaders("Content-type"))) {
            InputStream is = null;
            BufferedOutputStream bfos = null;
            try {
                HttpEntity httpEntity = response.getEntity();
                is = httpEntity.getContent();
                String fn = client.getUri().substring(client.getUri().lastIndexOf("/"));
                bfos = new BufferedOutputStream(new FileOutputStream(mPath + fn));
                byte[] buffer = new byte[4096];
                int count = 0;
                while ((count = is.read(buffer)) > 0) {
                    bfos.write(buffer, 0, count);
                }
                bfos.flush();
                bfos.close();
            } catch (Exception e) {
                e.printStackTrace();
                synchronized (this) {
                    if (client.getTries() < 3) {
                        mFailedDownloadClients.add(client);
                    }
                }
            } finally {
                try {
                    is.close();
                    bfos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        oneShortComplete(client);
    }

    public synchronized void reset() {
        stop();
        mLinkFilters.clear();
        mDownloadFilters.clear();
        mVisitedLinks.clear();
        mPendingLinks.clear();
    }

    public void setPath(String path) {
        if (new File(path).exists()) {
            mPath = path;
        }
    }

    public synchronized void stop() {
        for (Future<?> future : mFutures.values()) {
            future.cancel(false);
        }
        mPendingLinks.clear();
        mFutures.clear();
        mPendingLinks.clear();
        mLinkFilters.clear();
        mDownloadFilters.clear();
        mVisitedLinks.clear();
    }

    private void addNewLink(LinkTag lt, String host) {
        String uri = lt.getLink();
        if (uri == null || uri.length() <= 1 || uri.startsWith("#")) {
            return;
        }
        if (lt.isHTTPLikeLink()) {
            if (uri.startsWith("/")) {
                lt.setLink("http://" + host + uri);
            }
            addLink(lt);
        }
    }

    private String getHtml(HttpResponse response, InputStream stream) {
        try {
            StringBuilder html = new StringBuilder();
            String temp;
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "utf-8"));
            while ((temp = reader.readLine()) != null) {
                html.append(temp).append("\n");
            }
            FileWriter fileWriter = new FileWriter("e:\\tmp\\1.txt");
            fileWriter.write(html.toString());
            fileWriter.close();
            return html.toString();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private boolean isDownloadable(Header[] headers) {
        if (mDownloadFilters.isEmpty()) {
            return true;
        }
        for (DownloadFilter df : mDownloadFilters) {
            if (df.isDesired(headers))
                return true;
        }
        return false;
    }

    private boolean isHtmlResponse(Header[] headers) {
        for (Header header : headers) {
            if (header.getValue().startsWith("text/html")) {
                return true;
            }
        }
        return false;
    }

    private synchronized void oneShortComplete(HttpClientHelper client) {
        mFutures.remove(client);
        tryInvoke();
    }

    private void processNode(Node node, String host) {
        if (node instanceof LinkTag) // <a>
        {
            LinkTag link = (LinkTag) node;
            //System.out.println(link.toHtml());
            addNewLink(link, host);
        }
    }

    private synchronized void tryInvoke() {
        while (!mFailedDownloadClients.isEmpty() && mFutures.size() < MAX_LOADING_THREAD) {
            HttpClientHelper client = mFailedDownloadClients.remove(0);
            mFutures.put(client, mCachedExecutor.submit(client));
        }

        while (!mPendingLinks.isEmpty() && mFutures.size() < MAX_LOADING_THREAD) {
            LinkTag link = mPendingLinks.remove(0);
            HttpClientHelper client = new HttpClientHelper(link.getLink(), this);
            mFutures.put(client, mCachedExecutor.submit(client));
        }

        if (mFutures.isEmpty()) {
            mRoundCompleted.roundCompleted();
        }
    }

    private void visitHtml(String html, String encoding, final String host) {
        try {
            Parser parser = new Parser();
            parser.setInputHTML(html);
            parser.setEncoding(encoding);
            parser.visitAllNodesWith(new NodeVisitor() {

                @Override
                public void visitTag(Tag tag) {
                    processNode(tag, host);
                }
            });
        } catch (ParserException e) {
            e.printStackTrace();
        }
    }
}

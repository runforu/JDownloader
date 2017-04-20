package com.opera.traffic;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.Header;
import org.htmlparser.tags.LinkTag;

import com.opera.traffic.Control.RoundCompleted;

public class Launcher implements RoundCompleted {

    static class DomainFilter implements Control.LinkFilter {
        String mDomain;

        public DomainFilter(String domain) {
            mDomain = domain;
        }

        @Override
        public boolean isDesired(LinkTag lt) {
            if (lt.getLink().indexOf(mDomain) != -1) {
                return true;
            }
            return false;
        }
    }

    static class MimeFilter implements Control.DownloadFilter {

        private final String mMime;

        public MimeFilter(String mime) {
            mMime = mime;
        }

        @Override
        public boolean isDesired(Header[] headers) {
            for (Header header : headers) {
                if (header.getValue().startsWith(mMime)) {
                    return true;
                }
            }
            return false;
        }
    }

    static class RegexFilter implements Control.LinkFilter {
        Pattern mPattern;

        public RegexFilter(String regex) {
            mPattern = Pattern.compile(regex);
        }

        @Override
        public boolean isDesired(LinkTag lt) {
            Matcher matcher = mPattern.matcher(lt.getLink());
            if (matcher.lookingAt()) {
                return true;
            }
            return false;
        }
    }

    static class TitleFilter implements Control.LinkFilter {
        Pattern mPattern;

        public TitleFilter(String regex) {
            mPattern = Pattern.compile(regex);
        }

        @Override
        public boolean isDesired(LinkTag lt) {
            if (lt.toHtml().trim().isEmpty()) {
                return true;
            }
            Matcher matcher = mPattern.matcher(lt.toHtml().trim());
            if (matcher.matches()) {
                return true;
            }
            return false;
        }
    }

    static class WildcardFilter implements Control.LinkFilter {
        String mPattern;

        public WildcardFilter(String wc) {
            mPattern = wc.trim();
        }

        @Override
        public boolean isDesired(LinkTag lt) {
            return match(lt.getLink());
        }

        private boolean match(String src) {
            if (src == null)
                return false;

            boolean result = false;
            char ch;
            int positionLastStarMatch = -1;
            int positionLastStar = -1;
            int i = 0, j = 0;
            while (i < src.length()) {
                if (j >= mPattern.length()) {
                    result = false;
                    if (positionLastStarMatch != -1) {
                        i = positionLastStarMatch + 1;
                        j = positionLastStar + 1;
                        positionLastStarMatch++;
                        continue;
                    } else {
                        break;
                    }
                }

                if ((ch = mPattern.charAt(j)) == '*') {
                    if (j == mPattern.length() - 1) {
                        result = true;
                        break;
                    } else {
                        positionLastStarMatch = i;
                        positionLastStar = j;
                        j++;
                        continue;
                    }
                }

                if (src.charAt(i) != ch && ch != '?') {
                    result = false;
                    if (positionLastStarMatch != -1) {
                        i = positionLastStarMatch + 1;
                        j = positionLastStar + 1;
                        positionLastStarMatch++;
                        continue;
                    } else {
                        break;
                    }
                }
                j++;
                i++;
            }

            if (i == src.length() && j == mPattern.length()) {
                result = true;
            }
            return result;
        }
    }

    private final static String ORIGIN_URL =
    //"http://xia2.kekenet.com/Sound/2016/07/ffmc1401_2854161Vv8.mp3";
    "http://www.kekenet.com/Article/16167/";

    private static Map<String, String> PREDEFINE_MIME = new HashMap<String, String>() {
        {
            put("MP3", "audio/");
            put("APP", "application/");
            put("IMG", "image/");
        }
    };

    private final Control mControl = new Control(this);

    public static void main(String[] args) {
        Launcher launcher = new Launcher();
        if (args != null && args.length >= 1) {
            LinkTag lt = new LinkTag();
            lt.setLink(args[0]);
            launcher.mControl.addLink(lt);
        }
        launcher.start();
    }

    @Override
    public void roundCompleted() {
        List<LinkTag> list = mControl.getDesiredLinks();
        for (LinkTag lt : list) {
            System.out.println(lt.extractLink() + "\t" + lt.getChildrenHTML());
        }
        completeCommand();
    }

    // The roundCompleted do not always called in other thread, so make sure the notify is always in another thread.
    private void completeCommand() {
        new Thread(new Runnable() {

            @Override
            public void run() {
                synchronized (Launcher.this) {
                    Launcher.this.notify();
                }
            }
        }).start();
    }

    private boolean domainCommand(String input) {
        if (input.length() > "domain".length()) {
            String arg = input.substring("mime".length()).trim();
            String[] domains = arg.split("\\s+");

            if (domains.length == 0) {
                return false;
            }
            for (String s : domains) {
                mControl.addFilter(new DomainFilter(s));
            }
        }
        mControl.go();
        return true;
    }

    private boolean clearCommand(String input) {
        mControl.stop();
        return false;
    }

    private boolean goCommand(String input) {
        if (input.length() > "go".length()) {
            LinkTag lt = new LinkTag();
            lt.setLink(input.substring("go".length()).trim());
            mControl.addLink(lt);
        }
        mControl.go();
        return true;
    }

    private boolean mimeCommand(String input) {
        if (input.length() > "mime".length()) {
            String arg = input.substring("mime".length()).trim();
            String[] mimes = arg.split("\\s");
            if (mimes.length == 0) {
                return false;
            }

            for (String s : mimes) {
                if (PREDEFINE_MIME.containsKey(s)) {
                    mControl.addDownloadFilter(new MimeFilter(PREDEFINE_MIME.get(s)));
                } else {
                    mControl.addDownloadFilter(new MimeFilter(s));
                }
            }
        }
        return true;
    }

    private boolean regexCommand(String input) {
        String[] regexs = input.split("\\s+");
        if (regexs.length == 0) {
            return false;
        }

        for (String s : regexs) {
            if (s.startsWith("/") && s.endsWith("/")) {
                s = ".*" + s.substring(1, s.length() - 1);
                mControl.addFilter(new RegexFilter(s));
            }
        }
        mControl.go();
        return true;
    }

    private boolean saveCommand(String input) {
        if (input.length() > "save".length()) {
            mControl.setPath(input.substring("save".length()).trim());
            return true;
        }
        return false;
    }

    private void start() {
        while (true) {
            BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
            System.out.print("> ");
            try {
                String input = null;
                input = br.readLine();
                input = input.trim();
                if (input.startsWith("go")) {
                    goCommand(input);
                }
                if (input.startsWith("/")) {
                    if (!regexCommand(input)) {
                        continue;
                    }
                }
                if (input.startsWith("save")) {
                    saveCommand(input);
                }
                if (input.startsWith("title")) {
                    if (!titleCommand(input)) {
                        continue;
                    }
                }
                if (input.startsWith("wc")) {
                    if (!wildcardCommand(input)) {
                        continue;
                    }
                }
                if (input.startsWith("mime")) {
                    mimeCommand(input);
                    // download operation do not invoke a immediate go.
                    continue;
                }
                if (input.startsWith("domain")) {
                    if (!domainCommand(input)) {
                        continue;
                    }
                }
                if (input.startsWith("clear")) {
                    clearCommand(input);
                    continue;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            waitCommandComplete();
        }
    }

    private boolean titleCommand(String input) {
        if (input.length() > "title".length()) {
            String arg = input.substring("title".length()).trim();
            String[] regexs = arg.split("\\s+");
            if (regexs.length == 0) {
                return false;
            }
            for (String s : regexs) {
                mControl.addFilter(new TitleFilter(s));
            }
        }
        mControl.go();
        return true;
    }

    private void waitCommandComplete() {
        try {
            synchronized (this) {
                this.wait();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private boolean wildcardCommand(String input) {
        if (input.length() > "wc".length()) {
            String arg = input.substring("wc".length()).trim();
            String[] wcs = arg.split("\\s+");
            if (wcs.length == 0) {
                return false;
            }

            for (String s : wcs) {
                s = "*" + s + "*";
                mControl.addFilter(new WildcardFilter(s));
            }
        }
        mControl.go();
        return true;
    }
}

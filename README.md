JDownloader
==========

It is a interactive tool, which accepts user instruction and execute instruction. With its help, user can uses filters, including wildcard and regular expression and other filters, to reduce the found links, and then download many files without repeatedly clicking.


*Instruction:*
==============
1. go [link] -- get website from links currently stored and parse the htmls to get further links, link is optional, it will be added to links pools.
2. // -- indicate a regular expression filters, it cause the links currently stored filtered, it accept more than one regular expression seperated by white space, for example, /\d{8}/ /abc.*/ and so on.
3. save -- specify which folder to store the download files.
4. title [regex [regex]] -- like regular expression, but it applies on all raw html link tag, it accept more than one filter one time seperated by white space.
5. mime [mime_type [mime_type]] -- specify which download mimes is desired, it accept more than one mime type one time seperated by white space.
6. clear -- clear all the links and filters.
7. domain -- specify links domain which will be be fetched and analysed.
8. wc [wildcard [wildcard]] -- a common wildcard filter, for example, wc abc?bb ab*cc.

Good luck!
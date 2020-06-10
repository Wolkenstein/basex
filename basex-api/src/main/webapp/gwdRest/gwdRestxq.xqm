(:~
 : @author Peter Hinkelmanns
 :)
module namespace gwd = 'http://hinkelmanns.at/gwd/';

declare namespace tei = "http://www.tei-c.org/ns/1.0";
declare variable $db := db:open('GWD');

(:~
 : Generates a welcome page.
 : @return HTML page
 :)
declare
  %rest:GET
  %rest:path('gwd')
  %output:method('xhtml')
  %output:omit-xml-declaration('no')
  %output:doctype-public('-//W3C//DTD XHTML 1.0 Transitional//EN')
  %output:doctype-system('http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd')
function gwd:start(
) as element(Q{http://www.w3.org/1999/xhtml}html) {
  <html xmlns='http://www.w3.org/1999/xhtml'>
    <head>
      <title>GWD REST API</title>
    </head>
    <body>
      <p>{$db//tei:title/text()}</p>
    </body>
  </html>
};

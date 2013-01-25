
package no.priv.garshol.duke;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.KeywordAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.Version;

/**
 * Represents the Lucene index, and implements record linkage services
 * on top of it.
 */
public class LuceneDatabase implements Database {
  private Configuration config;
  private QueryResultTracker maintracker;
  private IndexWriter iwriter;
  private Directory directory;
  private IndexSearcher searcher;
  private Analyzer analyzer;
  // Deichman case:
  //  1 = 40 minutes
  //  4 = 48 minutes
  private final static int SEARCH_EXPANSION_FACTOR = 1;
  private int max_search_hits;
  private float min_relevance;

  public LuceneDatabase(Configuration config, boolean overwrite,
                        DatabaseProperties dbprops) {
    this.config = config;
    this.analyzer = new StandardAnalyzer(Version.LUCENE_CURRENT);
    this.maintracker = new QueryResultTracker();
    this.max_search_hits = dbprops.getMaxSearchHits();
    this.min_relevance = dbprops.getMinRelevance();

    try {
      openIndexes(overwrite);
      openSearchers();
    } catch (IOException e) {
      throw new DukeException(e);
    }
  }
  
  /**
   * Returns true iff the Lucene index is held in memory rather than
   * on disk.
   */
  public boolean isInMemory() {
    return (directory instanceof RAMDirectory);
  }

  /**
   * Add the record to the index.
   */
  public void index(Record record) {
    Document doc = new Document();

    for (String propname : record.getProperties()) {
      Property prop = config.getPropertyByName(propname);
      if (prop == null)
        throw new DukeConfigException("Record has property " + propname +
                                      " for which there is no configuration");

      Field.Index ix; // FIXME: could cache this. or get it from property
      if (prop.isIdProperty())
        ix = Field.Index.NOT_ANALYZED; // so findRecordById will work
      else // if (prop.isAnalyzedProperty())
        ix = Field.Index.ANALYZED;
      // FIXME: it turns out that with the StandardAnalyzer you can't have a
      // multi-token value that's not analyzed if you want to find it again...
      // else
      //   ix = Field.Index.NOT_ANALYZED;
      
      for (String v : record.getValues(propname)) {
        if (v.equals(""))
          continue; // FIXME: not sure if this is necessary

        doc.add(new Field(propname, v, Field.Store.YES, ix));
      }
    }

    try {
      iwriter.addDocument(doc);
    } catch (IOException e) {
      throw new DukeException(e);
    }
  }

  /**
   * Flushes all changes to disk.
   */
  public void commit() {
    try {
      if (searcher != null)
        searcher.close();

      // it turns out that IndexWriter.optimize actually slows
      // searches down, because it invalidates the cache. therefore
      // not calling it any more.
      // http://www.searchworkings.org/blog/-/blogs/uwe-says%3A-is-your-reader-atomic
      // iwriter.optimize();
    
      iwriter.commit();
      openSearchers();
    } catch (IOException e) {
      throw new DukeException(e);
    }
  }

  /**
   * Look up record by identity.
   */
  public Record findRecordById(String id) {
    Property idprop = config.getIdentityProperties().iterator().next();
    for (Record r : maintracker.lookup(idprop, id))
      if (r.getValue(idprop.getName()).equals(id))
        return r;

    return null; // not found
  }

  /**
   * Look up potentially matching records.
   */
  public Collection<Record> findCandidateMatches(Record record) {
    return maintracker.lookup(record);
  }
  
  /**
   * Stores state to disk and closes all open resources.
   */
  public void close() {
    try {
      iwriter.close();
      directory.close();
      if (searcher != null)
        searcher.close();
    } catch (IOException e) {
      throw new DukeException(e);
    }
  }

  // ----- INTERNALS

  private void openIndexes(boolean overwrite) {
    if (directory == null) {
      try {
        if (config.getPath() == null)
          directory = new RAMDirectory();
        else
          directory = FSDirectory.open(new File(config.getPath()));
        iwriter = new IndexWriter(directory, analyzer, overwrite,
                                  new IndexWriter.MaxFieldLength(25000));
        iwriter.commit(); // so that the searcher doesn't fail
      } catch (IndexNotFoundException e) {
        if (!overwrite) {
          // the index was not there, so make a new one
          directory = null; // ensure we really do try again
          openIndexes(true);
        }
        else
          throw new DukeException(e);
      } catch (IOException e) {
        throw new DukeException(e);
      }
    }
  }

  public void openSearchers() throws IOException { 
    searcher = new IndexSearcher(directory, true);
  }
  
  /**
   * These objects are used to estimate the size of the query result
   * we should ask Lucene for. This parameter is the single biggest
   * influence on matching performance, but setting it too low causes
   * matches to be missed. We therefore try hard to estimate it as
   * correctly as possible.
   *
   * <p>The reason this is a separate class is that we used to need
   * one of these for every property because the different properties
   * will behave differently.
   *
   * <p>FIXME: the class is badly named.
   * <p>FIXME: the class is not thread-safe.
   */
  class QueryResultTracker {
    private int limit;
    /**
     * Ring buffer containing n last search result sizes, except for
     * searches which found nothing.
     */
    private int[] prevsizes;
    private int sizeix; // position in prevsizes

    public QueryResultTracker() {
      this.limit = 100;
      this.prevsizes = new int[10];
    }

    public Collection<Record> lookup(Record record) {
      // first we build the combined query for all lookup properties
      BooleanQuery query = new BooleanQuery();
      for (Property prop : config.getLookupProperties()) {
        Collection<String> values = record.getValues(prop.getName());
        if (values == null)
          continue;
        for (String value : values)
          parseTokens(query, prop.getName(), value,
                      prop.getLookupBehaviour() == Property.Lookup.REQUIRED);
      }

      // then we perform the actual search
      return doQuery(query);
    }
    
    public Collection<Record> lookup(Property property, String value) {
      Query query = parseTokens(property.getName(), value);
      return doQuery(query);
    }

    private Collection<Record> doQuery(Query query) {
      List<Record> matches;
      try {
        ScoreDoc[] hits;

        int thislimit = Math.min(limit, max_search_hits);
        while (true) {
          hits = searcher.search(query, null, thislimit).scoreDocs;
          if (hits.length < thislimit || thislimit == max_search_hits)
            break;
          thislimit = thislimit * 5;
        }

        matches = new ArrayList(Math.min(hits.length, max_search_hits));
        for (int ix = 0; ix < hits.length &&
                         hits[ix].score >= min_relevance; ix++)
          matches.add(new DocumentRecord(hits[ix].doc,
                                         searcher.doc(hits[ix].doc)));

        if (hits.length > 0) {
          prevsizes[sizeix++] = matches.size();
          if (sizeix == prevsizes.length) {
            sizeix = 0;
            limit = Math.max((int) (average() * SEARCH_EXPANSION_FACTOR), limit);
          }
        }
        
      } catch (IOException e) {
        throw new DukeException(e);
      }
      return matches;
    }    
    
    /** 
     * Parses the query. Using this instead of a QueryParser
     * in order to avoid thread-safety issues with Lucene's query parser.
     * 
     * @param fieldName the name of the field
     * @param value the value of the field
     * @return the parsed query
     */
    protected Query parseTokens(String fieldName, String value) {
      BooleanQuery searchQuery = new BooleanQuery();
      if (value != null) {
        Analyzer analyzer = new KeywordAnalyzer();
        TokenStream tokenStream =
          analyzer.tokenStream(fieldName, new StringReader(value));
        CharTermAttribute attr =
          tokenStream.getAttribute(CharTermAttribute.class);
			
        try {
          while (tokenStream.incrementToken()) {
            String term = attr.toString();
            Query termQuery = new TermQuery(new Term(fieldName, term));
            searchQuery.add(termQuery, Occur.SHOULD);
          }
        } catch (IOException e) {
          throw new RuntimeException("Error parsing input string '"+value+"' "+
                                     "in field " + fieldName);
        }
      }
      
      return searchQuery;
    }

    /**
     * Parses Lucene query.
     * @param required Iff true, return only records matching this value.
     */
    protected void parseTokens(BooleanQuery parent, String fieldName,
                               String value, boolean required) {
      value = escapeLucene(value);
      if (value.length() == 0)
        return;

      TokenStream tokenStream =
        analyzer.tokenStream(fieldName, new StringReader(value));
      CharTermAttribute attr =
        tokenStream.getAttribute(CharTermAttribute.class);
			
      try {
        while (tokenStream.incrementToken()) {
          String term = attr.toString();
          Query termQuery = new TermQuery(new Term(fieldName, term));
          parent.add(termQuery, required ? Occur.MUST : Occur.SHOULD);
        }
      } catch (IOException e) {
        throw new RuntimeException("Error parsing input string '"+value+"' "+
                                   "in field " + fieldName);
      }
    }
    
    private double average() {
      int sum = 0;
      int ix = 0;
      for (; ix < prevsizes.length && prevsizes[ix] != 0; ix++)
        sum += prevsizes[ix];
      return sum / (double) ix;
    }
        
    private String cleanLucene(String query) {
      char[] tmp = new char[query.length()];
      int count = 0;
      for (int ix = 0; ix < query.length(); ix++) {
        char ch = query.charAt(ix);
        if (ch != '*' && ch != '?' && ch != '!' && ch != '&' && ch != '(' &&
            ch != ')' && ch != '-' && ch != '+' && ch != ':' && ch != '"' &&
            ch != '[' && ch != ']' && ch != '~' && ch != '{' && ch != '}' &&
            ch != '^' && ch != '|')
          tmp[count++] = ch;
      }
      
      return new String(tmp, 0, count).trim();
    }

    private String escapeLucene(String query) {
      char[] tmp = new char[query.length() * 2];
      int count = 0;
      for (int ix = 0; ix < query.length(); ix++) {
        char ch = query.charAt(ix);
        if (ch == '*' || ch == '?' || ch == '!' || ch == '&' || ch == '(' ||
            ch == ')' || ch == '-' || ch == '+' || ch == ':' || ch == '"' ||
            ch == '[' || ch == ']' || ch == '~' || ch == '{' || ch == '}' ||
            ch == '^' || ch == '|')
          tmp[count++] = '\\'; // these characters must be escaped
        tmp[count++] = ch;
      }
      
      return new String(tmp, 0, count).trim();
    }
  }
}
/*
 *  Copyright 2015 Carnegie Mellon University
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package edu.cmu.lti.oaqa.knn4qa.embed;


import java.io.*;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.cmu.lti.oaqa.knn4qa.memdb.DocEntry;
import edu.cmu.lti.oaqa.knn4qa.memdb.InMemForwardIndex;
import edu.cmu.lti.oaqa.knn4qa.simil.*;
import edu.cmu.lti.oaqa.knn4qa.utils.CompressUtils;
import edu.cmu.lti.oaqa.knn4qa.utils.VocabularyFilterAndRecoder;
import net.openhft.koloboke.collect.map.hash.*;

class ParsedEmbedRec {  
  static final String WS_SPLIT_PAT = "\\s+";

  /**
   *  Parses a string representation of an embedding,
   *  if the input is empty doesn't throw an exception, but
   *  merely sets mWord to an empty string and mVec to null.  
   *  
   * @param line    an input string (whole file line).
   * @param lineNum the line number of the input string.
   * 
   * @throws Exception
   */
  ParsedEmbedRec(String line, int lineNum) throws Exception {
    String parts[] = line.split(WS_SPLIT_PAT);
    mWord = parts[0];
    
    if (mWord.isEmpty()) {
      mVec = null;
    } else {
      int dim = parts.length - 1;
      mVec = new float[dim];
      String floatStr;
      for (int i = 0; i < dim; ++i) {
        floatStr = parts[i + 1];
        try {
          mVec[i] = Float.parseFloat(parts[i + 1]);
        } catch (NumberFormatException e) {
          throw new Exception(String.format(
              "Wrong format in line %d, can't parse float # %d: '%s'", lineNum, i+1, floatStr));           
        }
      }      
    }    
  }
  
  final String    mWord;
  final float []  mVec;
};

public class EmbeddingReaderAndRecoder {
  private static final int REPORT_INTERVAL_QTY = 50000;
  
  private static final Logger logger = LoggerFactory.getLogger(EmbeddingReaderAndRecoder.class);
  
  /**
   * Constructor: reads a file (can be compressed) in a text format (the
   * one that is used in Glove) and saves the mapping from strings to vectors.
   * 
   * <p>If the recoding object is specified, the class also keeps the mapping
   * from IDs to vectors. Note that the recoding object is also used as a filter:
   * if we can't find an ID for a given string, this string is ignored. 
   * If the recoding/filtering object is null, no filtering/recoding happens.</p>
   * 
   * <p>Note: this class (and the cosine similarity function) were cross-tested 
   * using the word2vec app <b>distance</b>. We converted the
   * word2vec sentences from the binary to text format. Then,
   * we searched for several identical words and compared output.</p>
   *  
   * @param textFileName 
   *            input file
   * @param filterAndRecoder
   *            an object used for filtering and recoding (can be null).
   * 
   * @throws Exception 
   */
  public EmbeddingReaderAndRecoder(String textFileName,
                             VocabularyFilterAndRecoder filterAndRecoder) throws Exception {
    BufferedReader fr = new BufferedReader(new 
              InputStreamReader(CompressUtils.createInputStream(textFileName)));        
    
    String line;
    int lineNum = 0;
    while ((line = fr.readLine()) != null) {
      ++lineNum;
      
      ParsedEmbedRec rec = new ParsedEmbedRec(line, lineNum);
            
      if (rec.mWord.isEmpty()) continue;

      if (mhStr2Vec.containsKey(rec.mWord)) {
        logger.info("Duplicate key: '" + rec.mWord + "' line: " + lineNum);
        continue;
      }
      
      if (0 == mDim) {
        mDim = rec.mVec.length;
        if (0 == mDim) {
          throw new Exception(String.format("Wrong format in line %d, no vector elements found", lineNum));
        }
      } else {
        if (mDim != rec.mVec.length)
          throw new Exception(String.format(
              "Wrong format in line %d, # of vector elements (%d) is different from preceeding lines (%d)", 
              rec.mVec.length, mDim));
      }
      
      normalizeL2(rec.mVec);
      
      mhStr2Vec.put(rec.mWord, rec.mVec);
      
      if (null != filterAndRecoder) {
        Integer id = filterAndRecoder.getWordId(rec.mWord);
        if (id != null) mhInt2Vec.put((int)id, rec.mVec);
      }
      
      if (lineNum % REPORT_INTERVAL_QTY == 0)
        logger.info(String.format("Loaded %d source word vectors from '%s'", 
                    lineNum, textFileName));

    }
    
    mZeroVector = new float[mDim];
    
    logger.info(String.format("Finished loading %d word vectors from '%s' (out of %d), dimensionality: %d", 
                              mhInt2Vec.size(), textFileName, lineNum, getDim()));
  }
  
  public static void normalizeL2(float[] vec) {
    float norm = 0;
    for (float f: vec) norm += f*f;
    norm = (float) Math.sqrt(norm);
    if (Math.abs(norm) >= DistanceFunctions.FLOAT_EPS) {
      for (int i = 0; i < vec.length; ++i)
        vec[i] /= norm;
    }
  }

  /**
   * Obtains an average vector for a given <b>white-spaced</b> delimited text.
   * 
   * <p>The function <b>ignores</b> unknown words.</p>
   * 
   * @param     text            input text
   * @param     normalizeL2     if true, the output vector is L2-normalized.
   * @return    a respective average vector.
   */
  public float[] getTextAverage(String text, boolean normalizeL2) {
    float[] res = new float[mDim];
    int qty = 0;
    
    for (String w : text.split(ParsedEmbedRec.WS_SPLIT_PAT)) {
      float[] vec = getVector(w);
      if (null != vec) {
        ++qty;
        for (int k = 0; k < mDim; ++k) { res[k] += vec[k]; }        
      }
    }
    
    if (qty > 0) {
      for (int k = 0; k < mDim; ++k) res[k] /= (float) qty; 
    }
    
    if (normalizeL2) normalizeL2(res);
    
    return res;
  }
  
  
  /**
   * Obtains an average <b>weighted</b> vector for a given sequence of word ids. 
   * 
   * <p>This is a version where embeddings are weighted by TF*IDF.
   * The function <b>ignores</b> unknown words.</p>
   * 
   * @param     text            input text
   * @param     simil           a similarity object (necessary to compute IDF)
   * @param     fieldIndex      an in-memory forward index (necessary to compute IDF)
   * @param     weightByIDF     if true, compute an IDF-weighted average
   * @param     normalizeL2     if true, the output vector is L2-normalized.
   * @return    a respective average vector.
   */  
  public float[] getDocAverage(DocEntry             doc,
                               QueryDocSimilarity   simil,
                               InMemForwardIndex    fieldIndex, 
                               boolean              weightByIDF,
                               boolean              normalizeL2) {
    float[] res = new float[mDim];
    int qty = 0;
    
    for (int iWord = 0; iWord < doc.mWordIds.length; ++iWord) {
      int wordId = doc.mWordIds[iWord];
      float[] vec = getVector(wordId);
      if (vec != null) {
        float mult = weightByIDF ? simil.getIDF(fieldIndex, wordId) : 1.0f;
        ++qty;
        for (int k = 0; k < mDim; ++k) { 
          res[k] += vec[k] * 
                    mult * 
                    doc.mQtys[iWord]; 
        }   
      }      
    }
    
    if (qty > 0) {
      for (int k = 0; k < mDim; ++k) res[k] /= (float) qty; 
    }
    
    if (normalizeL2) normalizeL2(res);    

    return res;
  }
  
  /**
   * Obtain dimensionality.
   * 
   * @return  the number of vectors' elements.
   */
  public int getDim() { return mDim; }
  
  /**
   * Obtains an embedding for a given string.
   * 
   * @param s   string
   * @return    an embedding vector or null, if the string is not found in the dictionary
   */
  public float[] getVector(String s) {
    return mhStr2Vec.get(s);
  }
  
  /**
   * Obtains an embedding by the word ID, it works only
   * if the non-null recoding object was specified in the constructor. 
   * 
   * @param id  word ID
   * @return an embedding vector, or null if the there's no such ID.
   */
  public float[] getVector(int id) {
    return mhInt2Vec.get(id);
  }
  
  /**
   * Obtains a vector for a given string. Returns an zero-vector,
   * if the string cannot be found
   * 
   * @param s   string
   * @return    an embedding vector or the zero vetor, if the string is not found in the dictionary
   */
  public float[] getVectorOrZero(String s) {
    float [] res = getVector(s);

    return res != null ? res : mZeroVector;
  }
  
  /**
   * A brute-force k-NN search
   * 
   * @param queryID             query identifier: e.g., a word or a document ID.
   * @param dist                a reference to the class that computes a distance
   * @param bExcludeExactMatch  exclude exact match???
   * @param k                   the k in k-NN.
   * @return An empty array of k most similar entries.
   */
  public VectorSearchEntry[] kNNSearch(String queryID,
                                        AbstractDistance dist,
                                        boolean bExcludeExactMatch, 
                                        int k) {
    float[] searchVec = getVector(queryID);
    
    if (searchVec != null) {
      PriorityQueue<VectorSearchEntry> q = new PriorityQueue<VectorSearchEntry>(k);      
      
      for (Map.Entry<String, float[]> e : mhStr2Vec.entrySet()) {
        float sim = dist.compute(searchVec, e.getValue());
               
        if (bExcludeExactMatch && e.getKey().equals(queryID)) continue;        

        if (q.size() < k) {
          VectorSearchEntry toAdd = new VectorSearchEntry(e.getKey(), sim);
          q.add(toAdd);
//          System.out.println("Add " + toAdd);
        }
        else if (sim < q.peek().mDist) {
          VectorSearchEntry toAdd = new VectorSearchEntry(e.getKey(), sim);
          q.add(toAdd);
          VectorSearchEntry toDel = q.poll();
//          System.out.println("Delete " + toDel + " add " + toAdd);
        }
      }
      int qty = q.size();
      VectorSearchEntry[] res = new VectorSearchEntry[qty];
      
      for (int i = 0; i < qty; ++i) {
        res[qty - i - 1] = q.poll();
      }
      return res;
    }
    
    return new VectorSearchEntry[0];
  }
  
  HashMap<String, float[]>          mhStr2Vec = new HashMap<String, float[]>();
  private HashIntObjMap<float []>   mhInt2Vec = HashIntObjMaps.<float []>newMutableMap();
    
  float[]                   mZeroVector;
  
  int       mDim = 0;
  
  /**
   * A simple test function that can also search for the closest embedding.
   * @throws Exception 
   * 
   */
  public static void main(String[] args) throws Exception {
    EmbeddingReaderAndRecoder wr = new EmbeddingReaderAndRecoder(args[0], null);
    
    BufferedReader sysInReader = new BufferedReader(new InputStreamReader(System.in));

    while (true) {
      String word = null;
      
      System.out.println("Input a search word or document ID: ");
      word = sysInReader.readLine();
      if (null == word) break;
      word = word.trim();
      if (word.isEmpty()) break;
      System.out.println("Input K for k-NN:");
      String ks = sysInReader.readLine();
      int k = Integer.parseInt(ks);
      if (k < 0) {
        System.err.println("Invalid k: " + k);
        System.exit(1);
      }
      System.out.println("Inpupt distance type (l2, cosine):");
      String dtype = sysInReader.readLine();
      try {      
        VectorSearchEntry[] res = wr.kNNSearch(word, AbstractDistance.create(dtype), true /* exclude the same word */, k);
        System.out.println("==== Results ====");
        for (VectorSearchEntry e: res) {
          System.out.println(e);
        }
        System.out.println("=================");
      } catch (Exception e) {        
        e.printStackTrace();
        System.err.println("Failed with an exception: " + e);
      }
   
    }
  }

}

package mt.decoder.efeat;

import java.util.*;
import java.io.*;

import mt.base.ARPALanguageModel;
import mt.base.ConcreteTranslationOption;
import mt.base.CoverageSet;
import mt.base.FeatureValue;
import mt.base.Featurizable;
import mt.base.ExtendedLexicalReorderingTable;
import mt.base.IOTools;
import mt.base.Sequence;
import mt.base.SimpleSequence;
import mt.base.ExtendedLexicalReorderingTable.ReorderingTypes;
import mt.decoder.feat.IncrementalFeaturizer;
import mt.decoder.feat.LexicalReorderingFeaturizer;
import mt.train.AlignmentGrid;
import mt.discrimreorder.DepUtils;
import mt.discrimreorder.TrainingExamples;

import edu.stanford.nlp.util.*;
import edu.stanford.nlp.ling.Datum;
import edu.stanford.nlp.ling.BasicDatum;
import edu.stanford.nlp.classify.*;
import edu.stanford.nlp.util.IString;
import edu.stanford.nlp.stats.ClassicCounter;

/**
 * Featurizer for a discriminative reordering model that uses typed dependency
 * features from the source (Chinese) side.
 * 
 * The classifier is built with the package: mt.discrimreorder
 * For details, look at the package.html for that package.
 * 
 * @author Pi-Chuan Chang
 *
 * @see mt.discrimreorder.ReorderingClassifier
 */
public class DiscrimTypedDependencyReorderingFeaturizer implements IncrementalFeaturizer<IString, String> {

  public static final String DEBUG_PROPERTY = "DebugDiscrimTypedDependencyReorderingFeaturizer";
  public static final boolean DEBUG = Boolean.parseBoolean(System.getProperty(DEBUG_PROPERTY, "false"));

  public static final String DETAILED_DEBUG_PROPERTY = "DetailedDebugDiscrimTypedDependencyReorderingFeaturizer";
  public static final boolean DETAILED_DEBUG = Boolean.parseBoolean(System.getProperty(DETAILED_DEBUG_PROPERTY, "false"));

  private Boolean useBoundaryOnly = false;
  private LineNumberReader pathReader = null;
  private List<TwoDimensionalMap<Integer,Integer,String>> pathMaps = null;
  private LinearClassifier lc = null;
  private TwoDimensionalMap<CoverageSet, CoverageSet, Double> featureCache;

  private int WINDOW = 1;

  public static final String FEATURE_NAME = "DiscrimReorder:Path";


  public DiscrimTypedDependencyReorderingFeaturizer(String... args) throws IOException {
    if(args.length < 1 || args.length > 3)
      throw new RuntimeException
        ("Usage: DiscrimTypedDependencyReorderingFeaturizer(pathFile,classifierFile,useBoundaryOnly?)");
    if (args.length > 2) {
      useBoundaryOnly = Boolean.parseBoolean(args[2]);
    }

    Runtime rt = Runtime.getRuntime();

    String classifierFile = args[1];

    long startTimeMillis = System.currentTimeMillis();
    long preTableLoadMemUsed = rt.totalMemory()-rt.freeMemory();

    lc = LinearClassifier.readClassifier(classifierFile);

    long postTableLoadMemUsed = rt.totalMemory() - rt.freeMemory();
    long loadTimeMillis = System.currentTimeMillis() - startTimeMillis;

    System.err.printf("\nDone loading discrim reorder classifier: %s (mem used: %d MiB time: %.3f s)\n", classifierFile,
                      (postTableLoadMemUsed - preTableLoadMemUsed)/(1024*1024), loadTimeMillis/1000.0);

    pathReader = IOTools.getReaderFromFile(args[0]);
    pathMaps = new ArrayList<TwoDimensionalMap<Integer,Integer,String>>();

    try {
      String pLine;
      while ((pLine = pathReader.readLine()) != null) {
        //System.err.printf("line %d read from path reader\n", pathReader.getLineNumber());
        TwoDimensionalMap<Integer,Integer,String> pathMap = new TwoDimensionalMap<Integer,Integer,String>();
        DepUtils.addPathsToMap(pLine, pathMap);
        //System.err.println("pathM size="+pathMap.entrySet().size());
        pathMaps.add(pathMap);
      }
    } catch (IOException e) {
      e.printStackTrace();
      throw new RuntimeException();
    }

    System.err.println("DiscrimTypedDependencyReorderingFeaturizer path file = "+args[0]);
    System.err.println("DiscrimTypedDependencyReorderingFeaturizer classifier file = "+classifierFile);
    System.err.println("DiscrimTypedDependencyReorderingFeaturizer useBoundaryOnly? = "+useBoundaryOnly);
    System.err.printf("DiscrimTypedDependencyReorderingFeaturizer path file has %d entries\n", pathMaps.size());
  }

  @Override
  public void initialize(List<ConcreteTranslationOption<IString>> options, Sequence<IString> foreign) { 
    //System.err.println("DiscrimTypedDependencyReorderingFeaturizer: initialize.");
    featureCache = new TwoDimensionalMap<CoverageSet, CoverageSet, Double>();

  } 

  int sentId = -1;
  public void reset() { 
    //System.err.println("DiscrimTypedDependencyReorderingFeaturizer: reset.");
    sentId++;
    featureCache = new TwoDimensionalMap<CoverageSet, CoverageSet, Double>();
    //System.err.println("sentId="+sentId);
  }

  @Override
  public FeatureValue<String> featurize(Featurizable<IString, String> f) { 
    List<String> features = new ArrayList<String>();

    CoverageSet cur = f.hyp.foreignCoverage;
    CoverageSet pre;
    if (f.prior != null)
      pre = f.prior.hyp.foreignCoverage;
    else
      pre = new CoverageSet();

    Double featVal = featureCache.get(cur, pre);
    if (featVal != null) {
      //System.err.println("pichuan: get val from cache");
      return new FeatureValue<String>(FEATURE_NAME, featVal);
    } else {
      TwoDimensionalMap<Integer,Integer,String> pathMap = pathMaps.get(sentId);

      CoverageSet fCoverage = f.hyp.foreignCoverage;
      int flen = f.foreignPhrase.size();
      int prevflen = 1;
      int prevelen = 1;
      int currC = f.foreignPosition;
      int prevC = -1;
      int prevE = -1;
      if (f.prior != null) {
        prevC = f.prior.foreignPosition;
        prevflen = f.prior.foreignPhrase.size();
        prevE = f.prior.translationPosition;
        prevelen = f.prior.translatedPhrase.size();
      }
      /*
        System.err.printf("----\n");
        System.err.printf("Partial translation (pos=%d): %s\n", f.translationPosition, f.partialTranslation);
        System.err.printf("Foreign sentence (pos=%d): %s\n", f.foreignPosition, f.foreignSentence);
        System.err.printf("Coverage: %s (size=%d)\n", fCoverage, f.hyp.foreignCoverage.length());
        System.err.printf("%s(%d) => %s(%d)\n", f.foreignPhrase, f.foreignPosition, f.translatedPhrase, f.translationPosition);
        
        if (f.prior == null) System.err.printf("Prior <s> => <s>\n");
        else System.err.printf("Prior %s(%d) => %s(%d)\n",        f.prior.foreignPhrase, f.prior.foreignPosition, f.prior.translatedPhrase, f.prior.translationPosition);
        //System.err.printf("j = %d, j' = %d, len1 = %d, len2 = %d\n", prevC, currC, prevflen, flen);
        System.err.printf("i = %d, j = %d, j' = %d, lenE = %d, lenC = %d\n, lenC2 = %d\n", prevE, prevC, currC, prevelen, prevflen, flen);
      */
      //System.err.println(pathMap);
      List<String> feats = extractWordFeatures(f.foreignSentence, prevC, prevflen, currC, flen,
                                               f.partialTranslation, prevE, prevelen, pathMap);
      /*
        for(String feat : feats) {
        System.err.println(" feat += "+feat);
        }
      */
      features.addAll(feats);

      Datum<TrainingExamples.ReorderingTypes,String> d = new BasicDatum(features);
      ClassicCounter<TrainingExamples.ReorderingTypes> logPs = lc.logProbabilityOf(d);
      double logP;
      if (prevC < currC) {
        logP = logPs.getCount(TrainingExamples.ReorderingTypes.ordered);
      } else if (prevC > currC) {
        logP = logPs.getCount(TrainingExamples.ReorderingTypes.distorted);
      } else {
        throw new RuntimeException();
      }
      /*
        System.err.printf("log p(%s|d) = %g\n",
        TrainingExamples.ReorderingTypes.ordered,
        logP_ordered);
      */
      //System.err.println("pichuan: insert val to cache");
      featureCache.put(cur, pre, logP);
      return new FeatureValue<String>(FEATURE_NAME, logP);
    }
  }

  @Override
  public List<FeatureValue<String>> listFeaturize(Featurizable<IString, String> f) {
    return null;
  }

  private IString getSourceWord(Sequence<IString> f, int idx) {
    if (idx < 0) return new IString("<s>");
    if (idx >= f.size()) return new IString("</s>");
    return f.get(idx);
  }

  private List<String> extractWordFeatures(Sequence<IString> f, int j, int lenC, int j2, int lenC2,
                                           Sequence<IString> e, int i, int lenE,
                                           TwoDimensionalMap<Integer,Integer,String> pathMap) {
    List<String> features = new ArrayList<String>();
    // SRCJ
    for (int J = j; J < j+lenC; J++) {
      features.addAll(extractFeatures_SRCJ(f, J));
    }

    // TGTI
    for (int I = i; I < i+lenE; I++) {
      features.addAll(extractFeatures_TGTI(e, I));
    }

    // path feature
    StringBuilder path = new StringBuilder("PATH:");
    if (pathMap.entrySet().size() > 0) {
      for (int J = j; J < j+lenC; J++) {
        for (int J2 = j2; J2 < j2+lenC2; J2++) {
          path.append(DepUtils.getPathName(J, J2, pathMap));
          features.add(path.toString());
        }
      }
    }
    
    return features;
  }

  private List<String> extractFeatures_SRCJ(Sequence<IString> f, int j) {
    List<String> features = new ArrayList<String>();
    // SRCJ
    for (int d = -WINDOW; d <= WINDOW; d++) {
      StringBuilder feature = new StringBuilder("SRCJ_");
      feature.append(d).append(":").append(getSourceWord(f, j+d));
      features.add(feature.toString());
    }
    return features;
  }

  private List<String> extractFeatures_TGTI(Sequence<IString> e, int i) {
    List<String> features = new ArrayList<String>();
    // TGTI
    for (int d = -WINDOW; d <= WINDOW; d++) {
      StringBuilder feature = new StringBuilder("TGT_");
      feature.append(d).append(":").append(getSourceWord(e, i+d));
      features.add(feature.toString());
    }
    return features;
  }
}
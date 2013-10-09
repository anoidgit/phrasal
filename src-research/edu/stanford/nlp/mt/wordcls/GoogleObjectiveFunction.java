package edu.stanford.nlp.mt.wordcls;

import java.util.Map;

import edu.stanford.nlp.mt.base.IString;
import edu.stanford.nlp.stats.ClassicCounter;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.stats.TwoDimensionalCounter;
import edu.stanford.nlp.util.Generics;

/**
 * The one-sided class model of Uszkoreit and Brants (2008).
 * 
 * @author Spence Green
 *
 */
public class GoogleObjectiveFunction {

  private double objValue = 0.0;

  private final ClustererState inputState;
  private final Map<IString, Integer> localWordToClass;
  
  private final Counter<Integer> deltaClassCount;
  private final TwoDimensionalCounter<Integer, NgramHistory> deltaClassHistoryCount;

  public GoogleObjectiveFunction(ClustererState input) {
    // Setup delta data structures
    this.inputState = input;
    localWordToClass = Generics.newHashMap(input.vocabularySubset.size());
    deltaClassCount = new ClassicCounter<Integer>(input.numClasses);
    deltaClassHistoryCount = new TwoDimensionalCounter<Integer,NgramHistory>();
    for (IString word : input.vocabularySubset) {
      int classId = input.wordToClass.get(word);
      localWordToClass.put(word, classId);
    }
    this.objValue = input.currentObjectiveValue;
  }

  public PartialStateUpdate cluster() {
    // Iterate over vocabulary
    for (IString word : inputState.vocabularySubset) {
      final Integer currentClass = localWordToClass.get(word);
      Integer argMaxClass = currentClass;
      double maxObjectiveValue = objValue;

      // Compute objective value under tentative moves
      for (int candidateClass = 0; candidateClass < inputState.numClasses; ++candidateClass) {
        if (candidateClass == currentClass) continue;
        
        double newObjective = move(word, currentClass, candidateClass, false);
        if (newObjective > maxObjectiveValue) {
          argMaxClass = candidateClass;
          maxObjectiveValue = newObjective;
        }
      }
      // Final move
      if (argMaxClass != currentClass) {
        // Should be the same computation, but with updates to state
        double newObjValue = move(word, currentClass, argMaxClass, true);
        assert newObjValue == maxObjectiveValue;
        objValue = newObjValue;
      }
    }
    return new PartialStateUpdate(localWordToClass, deltaClassCount, deltaClassHistoryCount);
  }

  /**
   * Explicitly update the local data structures and objective function value.
   * 
   * @param word
   * @param fromClass
   * @param toClass
   * @param updateDeltaState 
   */
  private double move(IString word, Integer fromClass, Integer toClass, boolean updateDeltaState) {
    final Counter<NgramHistory> fullHistoryFromClass = inputState.classHistoryCount.getCounter(fromClass);
    final Counter<NgramHistory> deltaFromClass = deltaClassHistoryCount.getCounter(fromClass);
    
    final Counter<NgramHistory> fullHistoryToClass = inputState.classHistoryCount.getCounter(toClass);
    final Counter<NgramHistory> deltaToClass = deltaClassHistoryCount.getCounter(toClass);
    
    final Counter<NgramHistory> historiesForWord = inputState.historyCount.getCounter(word);
    
    double fromClassCount = inputState.classCount.getCount(fromClass) + deltaClassCount.getCount(fromClass);
    assert fromClassCount > 0.0;
    double toClassCount = inputState.classCount.getCount(toClass) + deltaClassCount.getCount(toClass);
    
    //
    // Update first summation
    //
    double newObjValue = objValue;
    for (NgramHistory history : historiesForWord.keySet()) {
      double fromCount = fullHistoryFromClass.getCount(history) + deltaFromClass.getCount(history);
      assert fromCount > 0.0;
      double toCount = fullHistoryToClass.getCount(history) + deltaToClass.getCount(history);
      double historyCount = historiesForWord.getCount(history);
      
      // Remove old summands
      newObjValue -= fromCount*Math.log(fromCount);
      if (toCount > 0.0) {
        newObjValue -= toCount*Math.log(toCount);
      }
      
      // Update summands
      fromCount -= historyCount;
      toCount += historyCount;
      if (updateDeltaState) {
        deltaFromClass.decrementCount(history, historyCount);
        deltaToClass.incrementCount(history, historyCount);
      }
      
      // Add updated summands
      if (fromCount > 0.0) {
        newObjValue += fromCount*Math.log(fromCount);
      }
      newObjValue += toCount*Math.log(toCount);
    }

    //
    // Update second summation
    //
    // Remove old summands
    newObjValue += fromClassCount*Math.log(fromClassCount);
    if (toClassCount > 0.0) {
      newObjValue += toClassCount*Math.log(toClassCount);
    }

    // Update summands
    --fromClassCount;
    ++toClassCount;
    if (updateDeltaState) {
      deltaClassCount.decrementCount(fromClass);
      deltaClassCount.incrementCount(toClass);
    }
    
    // Add updated summands
    if (fromClassCount > 0.0) {
      newObjValue -= fromClassCount*Math.log(fromClassCount);
    }
    newObjValue -= toClassCount*Math.log(toClassCount);
    
    // Change the class assignment
    if (updateDeltaState) {
      localWordToClass.put(word, toClass);
    }
    
    return newObjValue;
  }
}


package no.priv.garshol.duke.comparators;

import no.priv.garshol.duke.Comparator;

/**
 * An implementation of the Levenshtein distance metric that uses
 * weights, so that not all editing operations are considered equal.
 * Useful explanation: http://www.let.rug.nl/kleiweg/lev/levenshtein.html
 */
public class WeightedLevenshtein implements Comparator {
  private WeightEstimator weight;

  public WeightedLevenshtein() {
    this.weight = new WeightEstimator();
  }
  
  public double compare(String s1, String s2) {   
    // if the strings are equal we can stop right here.
    if (s1.equals(s2))
      return 1.0;

    // we couldn't shortcut, so now we go ahead and compute the full
    // matrix
    int len = Math.min(s1.length(), s2.length());
    double dist = distance(s1, s2, weight);
    return 1.0 - (dist / ((double) len));
  }

  public boolean isTokenized() {
    return true;
  }

  public static double distance(String s1, String s2, WeightEstimator weight) {
    if (s1.length() == 0)
      return s2.length();
    if (s2.length() == 0)
      return s1.length();

    int s1len = s1.length();
    // we use a flat array for better performance. we address it by
    // s1ix + s1len * s2ix. this modification improves performance
    // by about 30%, which is definitely worth the extra complexity.
    double[] matrix = new double[(s1len + 1) * (s2.length() + 1)];
    for (int col = 0; col <= s2.length(); col++)
      matrix[col * s1len] = col;
    for (int row = 0; row <= s1len; row++)
      matrix[row] = row;

    for (int ix1 = 0; ix1 < s1len; ix1++) {
      char ch1 = s1.charAt(ix1);
      for (int ix2 = 0; ix2 < s2.length(); ix2++) {
        double cost;
        char ch2 = s2.charAt(ix2);
        if (ch1 == ch2)
          cost = 0;
        else
          cost = weight.substitute(ch1, s2.charAt(ix2));

        double left = matrix[ix1 + ((ix2 + 1) * s1len)] +
                     weight.delete(ch1);
        double above = matrix[ix1 + 1 + (ix2 * s1len)] +
                     weight.insert(ch2);
        double aboveleft = matrix[ix1 + (ix2 * s1len)] + cost;
        matrix[ix1 + 1 + ((ix2 + 1) * s1len)] =
          Math.min(left, Math.min(above, aboveleft));
      }
    }

    // for (int ix1 = 0; ix1 <= s1len; ix1++) {
    //   for (int ix2 = 0; ix2 <= s2.length(); ix2++) {
    //     System.out.print(matrix[ix1 + (ix2 * s1len)] + " ");
    //   }
    //   System.out.println();
    // }
        
    return matrix[s1len + (s2.length() * s1len)];
  }
  
  // optimizes by returning 0.0 as soon as we know total difference is
  // larger than 0.5, which happens when the distance is greater than
  // maxlen.
  //
  // on at least one use case, this optimization shaves 15% off the
  // total execution time.
  public static int optimizedDistance(String s1, String s2, int maxlen) {
    if (s1.length() == 0)
      return s2.length();
    if (s2.length() == 0)
      return s1.length();

    int maxdist = Math.min(s1.length(), s2.length()) / 2;

    int s1len = s1.length();
    // we use a flat array for better performance. we address it by
    // s1ix + s1len * s2ix. this modification improves performance
    // by about 30%, which is definitely worth the extra complexity.
    int[] matrix = new int[(s1len + 1) * (s2.length() + 1)];
    for (int col = 0; col <= s2.length(); col++)
      matrix[col * s1len] = col;
    for (int row = 0; row <= s1len; row++)
      matrix[row] = row;

    for (int ix1 = 0; ix1 < s1len; ix1++) {
      char ch1 = s1.charAt(ix1);
      for (int ix2 = 0; ix2 < s2.length(); ix2++) {
        int cost;
        if (ch1 == s2.charAt(ix2))
          cost = 0;
        else
          cost = 1;

        int left = matrix[ix1 + ((ix2 + 1) * s1len)] + 1;
        int above = matrix[ix1 + 1 + (ix2 * s1len)] + 1;
        int aboveleft = matrix[ix1 + (ix2 * s1len)] + cost;
        int distance = Math.min(left, Math.min(above, aboveleft));
        if (ix1 == ix2 && distance > maxdist)
           return distance;
        matrix[ix1 + 1 + ((ix2 + 1) * s1len)] = distance;
      }
    }
    
    return matrix[s1len + (s2.length() * s1len)];
  }

  public class WeightEstimator {
    public WeightEstimator() {
    }
    
    public double substitute(char ch1, char ch2) {
      return Math.max(insert(ch1), insert(ch2));
    }
    
    public double delete(char ch) {
      return insert(ch);
    }

    public double insert(char ch) {
      if ((ch >= 'a' && ch <= 'z') ||
          (ch >= 'A' && ch <= 'Z'))
        return 1.0;
      else if (ch >= '0' && ch <= '9')
        return 10.0;
      else if (ch == ' ' || ch == '\'' || ch == ',' || ch == '-')
        return 0.1;
      else
        return 1.0;
    }
  }
}
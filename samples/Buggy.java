package samples;

import java.util.ArrayList;
import java.util.List;

/**
 * Deliberately broken sample file used to demo the analyzer.
 *
 * It contains two families of defects:
 *   - syntactic patterns the classic AST rules can match
 *   - semantic defects only a model that understands intent can spot
 *
 * Do not fix these. They are the demo.
 */
public class Buggy {

    private List<String> inventory = new ArrayList<>();

    /**
     * Defect: compares Strings by reference instead of content.
     * Classic pass should flag this.
     */
    public boolean isAdmin(String role) {
        if (role == "admin") {
            return true;
        }
        return false;
    }

    /**
     * Defect: the loop runs one iteration past the end of the array.
     * Purely syntactic rules cannot see this. The AI pass should.
     */
    public int findLargest(int[] numbers) {
        int largest = numbers[0];
        for (int i = 0; i <= numbers.length; i++) {
            if (numbers[i] > largest) {
                largest = numbers[i];
            }
        }
        return largest;
    }

    /**
     * Defect: the method promises an average but returns the raw sum.
     * The name lies. Only a semantic reviewer catches this.
     */
    public double calculateAverage(int[] scores) {
        double total = 0;
        for (int score : scores) {
            total = total + score;
        }
        return total;
    }

    /**
     * Defect: unused local variable, and dead code after the return.
     * Classic pass should flag both.
     */
    public String describeInventory() {
        int itemCount = inventory.size();
        String separator = ", ";

        return "Inventory has " + itemCount + " items";
        inventory.clear();
    }

    /**
     * Defect: the exception is swallowed in silence.
     * Classic pass should flag the empty catch.
     */
    public int parseQuantity(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
        }
        return 0;
    }

    /**
     * Defect: the discount is applied to the wrong side of the operation,
     * so a 20% discount makes the price go up. Semantic, not syntactic.
     */
    public double applyDiscount(double price, double discountPercent) {
        return price * (1 + discountPercent / 100);
    }

    public static void main(String[] args) {
        Buggy demo = new Buggy();
        System.out.println(demo.isAdmin("admin"));
        System.out.println(demo.applyDiscount(100.0, 20.0));
    }
}

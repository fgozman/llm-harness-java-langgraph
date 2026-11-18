package com.example.organizer.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class KeywordContentCategorizerTest {

    @Test
    void recognizesFinanceContent() {
        assertThat(KeywordContentCategorizer.categorize("a.txt", "Invoice: please send the payment to our bank account."))
                .isEqualTo("finance");
    }

    @Test
    void recognizesCookingContent() {
        assertThat(KeywordContentCategorizer.categorize("b.txt", "Recipe: mix flour, eggs and milk, then bake in the oven."))
                .isEqualTo("cooking");
    }

    @Test
    void fallsBackToUncategorizedWhenNothingMatches() {
        assertThat(KeywordContentCategorizer.categorize("c.txt", "Call grandma on Sunday.")).isEqualTo("uncategorized");
    }

    @RepeatedTest(5)
    void resolvesTiesByTheOrderOfTheCategories() {
        // One finance keyword and one travel keyword: finance is listed first.
        assertThat(KeywordContentCategorizer.categorize("d.txt", "hotel invoice")).isEqualTo("finance");
    }
}

package com.onec.posting;

import com.onec.fixtures.TestSalesRegister;
import com.onec.fixtures.TestStockRegister;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PostingContextTest {

    @Test
    void movements_returnsCollectionForRegisterClass() {
        PostingContext context = new PostingContext();
        RegisterMovementCollection<TestStockRegister> collection =
                context.movements(TestStockRegister.class);

        assertThat(collection).isNotNull();
        assertThat(collection.getRegisterClass()).isEqualTo(TestStockRegister.class);
    }

    @Test
    void movements_calledTwice_returnsSameCollection() {
        PostingContext context = new PostingContext();
        var first = context.movements(TestStockRegister.class);
        var second = context.movements(TestStockRegister.class);

        assertThat(first).isSameAs(second);
    }

    @Test
    void allMovements_returnsAllCollections() {
        PostingContext context = new PostingContext();
        context.movements(TestStockRegister.class);
        context.movements(TestSalesRegister.class);

        assertThat(context.allMovements()).hasSize(2);
    }
}

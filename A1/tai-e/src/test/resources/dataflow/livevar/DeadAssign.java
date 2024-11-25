class DeadAssign {
    int deadAssign() {
        int a, b, c;
        a = 0; // dead assignment
        a = 1;
        b = a * 2; // dead assignment
        c = 3;
        return c;
    }
}
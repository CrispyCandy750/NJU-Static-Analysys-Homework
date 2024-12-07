class InfiniteProgram {
    public static void main(String[] args) {
        A a = new A();
        A a2 = a;
        A a3 = a2;
        a = a3;
    }
}

class A {
    void recusite() {
        this.recusite();
    }
}
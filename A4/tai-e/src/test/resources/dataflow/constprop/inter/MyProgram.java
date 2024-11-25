class MyProgram {
    public static void main(String[] args) {
        int b = 10;
        MyProgram myProgram = new MyProgram();
        myProgram.abs(b);
        b = b + b;
    }

    int abs(int a) {
        if (a >= 0) {
            return a;
        } else {
            return -a;
        }
    }
}
class MyProgram {

    void ifAndSwitchBranch() {
        int x = 10;
        int y = 1;
        int z;
        if (x > y) {
            x = 1;
            y = x << 3;
            switch (y) {
                case 2:
                    use(2);
                    break;  // unreachable case
                case 4:
                    use(4);
                    break; // unreachable case
                case 8:
                    use(8);
                    break;
                default:
                    use(666);
                    break; // unreachable case
            }
        } else {
            z = 200; // unreachable branch
        }
    }

    void use(int x) {
    }
}
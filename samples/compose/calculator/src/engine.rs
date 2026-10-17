//! Arithmetic and display formatting, kept apart from the UI so both can be tested
//! without a renderer.

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Operation {
    Add,
    Subtract,
    Multiply,
    Divide,
}

impl Operation {
    fn apply(self, left: f64, right: f64) -> f64 {
        match self {
            Self::Add => left + right,
            Self::Subtract => left - right,
            Self::Multiply => left * right,
            Self::Divide => left / right,
        }
    }

    fn symbol(self) -> &'static str {
        match self {
            Self::Add => "+",
            Self::Subtract => "\u{2212}",
            Self::Multiply => "\u{00d7}",
            Self::Divide => "\u{00f7}",
        }
    }

    fn from_label(label: &str) -> Option<Self> {
        match label {
            "+" => Some(Self::Add),
            "\u{2212}" | "-" => Some(Self::Subtract),
            "\u{00d7}" | "*" | "x" | "X" => Some(Self::Multiply),
            "\u{00f7}" | "/" => Some(Self::Divide),
            _ => None,
        }
    }
}

/// Digits kept in an entry. Past this a double cannot tell two entries apart, so more
/// digits would only be a longer lie.
const MAX_ENTRY_DIGITS: usize = 15;

/// Significant digits shown. A double carries between 15 and 17, and the last two or three
/// are where the binary representation shows through: `0.1 + 0.2` is `0.3` at twelve
/// significant digits and `0.30000000000000004` at seventeen.
const SIGNIFICANT_DIGITS: i32 = 12;

/// Values at or above this are shown in exponent form, because the plain form would be
/// longer than the significant digits that back it.
const EXPONENT_ABOVE: f64 = 1e12;
/// Values below this, and not zero, are shown in exponent form for the same reason.
const EXPONENT_BELOW: f64 = 1e-9;

/// A number as a calculator shows it: no trailing zeros, no false precision, and an
/// exponent only where the plain form would be unreadable.
pub fn format_number(value: f64) -> String {
    if !value.is_finite() {
        return "Error".to_owned();
    }
    if value == 0.0 {
        return "0".to_owned();
    }
    let magnitude = value.abs();
    if magnitude >= EXPONENT_ABOVE || magnitude < EXPONENT_BELOW {
        return format_exponent(value);
    }
    // Leading zeros are not significant, so a value below one gets more decimals rather
    // than fewer: this is what keeps `1/3` at twelve threes instead of eleven.
    let integer_digits = magnitude.log10().floor() as i32 + 1;
    let decimals = (SIGNIFICANT_DIGITS - integer_digits).clamp(0, 15) as usize;
    let mut text = format!("{value:.decimals$}");
    if text.contains('.') {
        text = text.trim_end_matches('0').trim_end_matches('.').to_owned();
    }
    if text == "-0" {
        return "0".to_owned();
    }
    text
}

fn format_exponent(value: f64) -> String {
    let text = format!("{:.*e}", (SIGNIFICANT_DIGITS - 1) as usize, value);
    let Some((mantissa, exponent)) = text.split_once('e') else {
        return text;
    };
    let mantissa = if mantissa.contains('.') {
        mantissa.trim_end_matches('0').trim_end_matches('.')
    } else {
        mantissa
    };
    if exponent.starts_with('-') {
        format!("{mantissa}e{exponent}")
    } else {
        format!("{mantissa}e+{exponent}")
    }
}

/// A calculation that finished, for whoever keeps the tape.
///
/// The expression is rebuilt from the two operands and the operator rather than recorded
/// as it was typed, so `2 + 3 = = =` writes three lines that each say what they actually
/// worked out instead of three copies of the keys that were pressed.
#[derive(Clone, Debug, PartialEq)]
pub struct Completed {
    pub expression: String,
    pub result: String,
    pub value: f64,
    /// Whether the answer is one a calculator can show. Dividing by zero and overflowing
    /// a double both land here, and neither belongs on a tape.
    pub failed: bool,
}

/// The calculator's whole state.
///
/// `entry` is what the user is typing right now. When it is empty the display shows
/// `value`, which is the running result.
/// The memory keys, in the order the reference prints them.
///
/// Every desk calculator has had these, and both the Windows and the Deepin reference
/// give them a row of their own above the keypad. They are a second register beside the
/// running value: somewhere to put a number while working out the next one.
pub const MEMORY_KEYS: [&str; 6] = ["MC", "MR", "M+", "M\u{2212}", "MS", "M\u{25be}"];

#[derive(Clone, Debug)]
pub struct Calculator {
    entry: String,
    typing: bool,
    value: f64,
    pending: Option<Operation>,
    /// The operation and right operand to reuse when `=` is pressed again.
    repeat: Option<(Operation, f64)>,
    error: bool,
    /// The last finished calculation, waiting to be taken. Untaken, it is simply
    /// overwritten: whoever wanted it had a chance after the key that produced it.
    completed: Option<Completed>,
    /// The second register, and whether anything was ever put in it.
    ///
    /// The flag is not `memory != 0.0`: storing a zero is something a person does on
    /// purpose, and a recall key that goes dead when you store a zero is a key that
    /// disagrees with what you just did.
    memory: f64,
    memory_set: bool,
}

impl Default for Calculator {
    fn default() -> Self {
        Self::new()
    }
}

impl Calculator {
    pub fn new() -> Self {
        Self {
            entry: String::new(),
            typing: false,
            value: 0.0,
            pending: None,
            repeat: None,
            error: false,
            completed: None,
            memory: 0.0,
            memory_set: false,
        }
    }

    /// Whether the memory holds anything. Recalling and clearing are dead keys until it
    /// does, which is what the reference greys out.
    pub fn memory_set(&self) -> bool {
        self.memory_set
    }

    /// What the big line reads.
    pub fn display(&self) -> String {
        if self.error {
            return "Error".to_owned();
        }
        if self.typing {
            return match self.entry.as_str() {
                "" => "0".to_owned(),
                "-" => "-0".to_owned(),
                entry => entry.to_owned(),
            };
        }
        format_number(self.value)
    }

    /// The small line above the display: the running value and the operation waiting for
    /// its right-hand side.
    pub fn status(&self) -> String {
        match self.pending {
            Some(operation) => format!("{} {}", format_number(self.value), operation.symbol()),
            None => String::new(),
        }
    }

    /// Hands over the last finished calculation and forgets it, so a caller that asks
    /// after every key gets each one exactly once.
    pub fn take_completed(&mut self) -> Option<Completed> {
        self.completed.take()
    }

    /// Puts a number back into the entry, as if it had just been typed.
    ///
    /// Typed rather than assigned, because the two behave differently: a recalled number
    /// that replaced the running value would swallow a pending operation, so `5 +` then a
    /// recall of 42 would read 42 instead of `5 + 42`.
    pub fn recall(&mut self, value: f64) {
        if self.error {
            *self = Self::new();
        }
        self.entry = format_number(value);
        self.typing = true;
    }

    /// The value the next operation will use: what is being typed, or the running result.
    fn operand(&self) -> f64 {
        if self.typing {
            self.entry.parse().unwrap_or(0.0)
        } else {
            self.value
        }
    }

    fn settle(&mut self, result: f64) {
        self.value = result;
        self.entry.clear();
        self.typing = false;
        self.error = !result.is_finite();
    }

    /// One key, named by the label printed on it.
    pub fn press(&mut self, label: &str) {
        match label {
            "MC" => {
                self.memory = 0.0;
                self.memory_set = false;
            }
            "MR" => {
                if self.memory_set {
                    let memory = self.memory;
                    self.recall(memory);
                }
            }
            "MS" => self.store(self.shown_value()),
            "M+" => self.store(self.memory + self.shown_value()),
            "M\u{2212}" | "M-" => self.store(self.memory - self.shown_value()),
            "C" => {
                // Clearing clears the calculation, not the memory. Every one of the
                // three references keeps the two apart, and a C that emptied the memory
                // would throw away the number that was put somewhere safe.
                let (memory, memory_set) = (self.memory, self.memory_set);
                *self = Self::new();
                self.memory = memory;
                self.memory_set = memory_set;
            }
            "\u{232b}" => self.backspace(),
            "%" => self.percent(),
            "\u{00b1}" => self.flip_sign(),
            "." => self.decimal_point(),
            "=" => self.equals(),
            digit if digit.len() == 1 && digit.as_bytes()[0].is_ascii_digit() => {
                self.digit(digit.as_bytes()[0] as char);
            }
            operator => {
                if let Some(operation) = Operation::from_label(operator) {
                    self.operation(operation);
                }
            }
        }
    }

    /// The number the big line is reading, which is what a memory key acts on.
    fn shown_value(&self) -> f64 {
        if self.error { 0.0 } else { self.operand() }
    }

    fn store(&mut self, value: f64) {
        if self.error || !value.is_finite() {
            return;
        }
        self.memory = value;
        self.memory_set = true;
    }

    fn digit(&mut self, digit: char) {
        if self.error {
            *self = Self::new();
        }
        if !self.typing {
            self.entry.clear();
            self.typing = true;
        }
        if self.entry == "0" {
            self.entry.clear();
        } else if self.entry == "-0" {
            self.entry = "-".to_owned();
        }
        if self.entry.chars().filter(char::is_ascii_digit).count() >= MAX_ENTRY_DIGITS {
            return;
        }
        self.entry.push(digit);
    }

    fn decimal_point(&mut self) {
        if self.error {
            *self = Self::new();
        }
        if !self.typing {
            self.entry = "0.".to_owned();
            self.typing = true;
            return;
        }
        if self.entry.contains('.') {
            return;
        }
        if self.entry.is_empty() || self.entry == "-" {
            self.entry.push('0');
        }
        self.entry.push('.');
    }

    fn flip_sign(&mut self) {
        if self.typing {
            if let Some(rest) = self.entry.strip_prefix('-') {
                self.entry = rest.to_owned();
            } else {
                self.entry.insert(0, '-');
            }
        } else if self.value != 0.0 {
            self.value = -self.value;
        }
    }

    /// Percent reads as "of the running value" next to a plus or a minus, which is what
    /// makes `200 + 10 %` come out as 220, and as "divided by a hundred" everywhere else.
    fn percent(&mut self) {
        if self.error {
            return;
        }
        let operand = self.operand();
        let result = match self.pending {
            Some(Operation::Add) | Some(Operation::Subtract) => self.value * operand / 100.0,
            _ => operand / 100.0,
        };
        self.entry = format_number(result);
        self.typing = true;
    }

    fn backspace(&mut self) {
        if self.error {
            *self = Self::new();
            return;
        }
        if self.typing {
            self.entry.pop();
            if self.entry.is_empty() || self.entry == "-" {
                self.entry.clear();
                self.typing = false;
                self.value = 0.0;
            }
        } else {
            self.value = 0.0;
        }
    }

    fn operation(&mut self, operation: Operation) {
        if self.error {
            return;
        }
        let operand = self.operand();
        let result = match self.pending {
            // Two operators in a row replace each other rather than folding a value in
            // twice, so `3 + ×` means `3 ×`.
            Some(pending) if self.typing => pending.apply(self.value, operand),
            _ => operand,
        };
        self.settle(result);
        self.pending = Some(operation);
        self.repeat = None;
    }

    fn equals(&mut self) {
        if self.error {
            return;
        }
        if let Some(pending) = self.pending {
            let left = self.value;
            let operand = self.operand();
            let result = pending.apply(left, operand);
            self.record(left, pending, operand, result);
            self.settle(result);
            self.repeat = Some((pending, operand));
            self.pending = None;
        } else if let Some((operation, operand)) = self.repeat {
            let left = self.value;
            let result = operation.apply(left, operand);
            self.record(left, operation, operand, result);
            self.settle(result);
        } else {
            // Equals with nothing waiting works nothing out, so there is nothing to
            // record. A tape line reading `7 = 7` says only that a key was pressed.
            let operand = self.operand();
            self.settle(operand);
        }
    }

    fn record(&mut self, left: f64, operation: Operation, right: f64, result: f64) {
        self.completed = Some(Completed {
            expression: format!(
                "{} {} {}",
                format_number(left),
                operation.symbol(),
                format_number(right)
            ),
            result: format_number(result),
            value: result,
            failed: !result.is_finite(),
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The key a character stands for, so a test can write a sequence as a string.
    ///
    /// The keypad is labelled with the typographic operators, and nobody wants to write
    /// those in a test, so this is the translation and it lives here rather than in the
    /// engine: the engine takes the labels its keys carry and nothing else.
    fn key(character: char) -> &'static str {
        match character {
            '0' => "0",
            '1' => "1",
            '2' => "2",
            '3' => "3",
            '4' => "4",
            '5' => "5",
            '6' => "6",
            '7' => "7",
            '8' => "8",
            '9' => "9",
            '.' => ".",
            '+' => "+",
            '-' => "\u{2212}",
            '*' => "\u{00d7}",
            '/' => "\u{00f7}",
            '%' => "%",
            '=' => "=",
            'c' => "C",
            '~' => "\u{00b1}",
            '<' => "\u{232b}",
            other => panic!("no key is labelled {other}"),
        }
    }

    fn press_all(calculator: &mut Calculator, keys: &str) {
        for character in keys.chars() {
            calculator.press(key(character));
        }
    }

    fn run(keys: &str) -> String {
        let mut calculator = Calculator::new();
        press_all(&mut calculator, keys);
        calculator.display()
    }

    /// The memory keys are a second register: something to put a number in while the
    /// next one is worked out, which is what both the Windows and the Deepin reference
    /// give a row of their own.
    #[test]
    fn fr22_the_memory_keys_store_and_recall() {
        let mut calculator = Calculator::new();
        assert!(
            !calculator.memory_set(),
            "a new calculator should have nothing in memory"
        );

        press_all(&mut calculator, "42");
        calculator.press("MS");
        assert!(calculator.memory_set());

        press_all(&mut calculator, "c7");
        calculator.press("M+");
        press_all(&mut calculator, "c");
        calculator.press("MR");
        assert_eq!(
            calculator.display(),
            "49",
            "M+ should add to what is stored"
        );

        // A recalled number is typed rather than assigned, so it becomes the right-hand
        // side of a waiting operation instead of replacing the running value.
        press_all(&mut calculator, "c1+");
        calculator.press("MR");
        press_all(&mut calculator, "=");
        assert_eq!(calculator.display(), "50");

        calculator.press("M\u{2212}");
        calculator.press("MR");
        assert_eq!(
            calculator.display(),
            "-1",
            "M- should subtract what is shown"
        );

        calculator.press("MC");
        assert!(!calculator.memory_set(), "MC should empty the memory");
    }

    /// Clearing the calculation is not clearing the memory. All three references keep
    /// the two apart, and a number put somewhere safe should survive the key that starts
    /// the next calculation.
    #[test]
    fn fr22_clearing_the_entry_leaves_the_memory_alone() {
        let mut calculator = Calculator::new();
        press_all(&mut calculator, "8");
        calculator.press("MS");
        press_all(&mut calculator, "c");
        assert!(calculator.memory_set());
        calculator.press("MR");
        assert_eq!(calculator.display(), "8");
    }

    #[test]
    fn adds_and_multiplies() {
        assert_eq!(run("12+34="), "46");
        assert_eq!(run("6*7="), "42");
        assert_eq!(run("9-11="), "-2");
    }

    #[test]
    fn chains_operations_left_to_right() {
        assert_eq!(run("2+3*4="), "20");
    }

    #[test]
    fn repeated_equals_repeats_the_last_operation() {
        assert_eq!(run("2+3==="), "11");
    }

    #[test]
    fn a_repeating_result_is_cut_at_twelve_significant_digits() {
        assert_eq!(run("1/3="), "0.333333333333");
        assert_eq!(run("2/3="), "0.666666666667");
    }

    #[test]
    fn binary_representation_does_not_leak_into_the_display() {
        assert_eq!(run("0.1+0.2="), "0.3");
    }

    #[test]
    fn a_long_result_falls_back_to_an_exponent() {
        assert_eq!(run("99999999*99999999="), "9.9999998e+15");
        assert_eq!(format_number(0.000000000123), "1.23e-10");
    }

    #[test]
    fn dividing_by_zero_reports_an_error_and_recovers() {
        let mut calculator = Calculator::new();
        press_all(&mut calculator, "5/0=");
        assert_eq!(calculator.display(), "Error");
        calculator.press("C");
        assert_eq!(calculator.display(), "0");
    }

    #[test]
    fn decimal_point_is_added_once() {
        assert_eq!(run("1.2.3"), "1.23");
        assert_eq!(run("."), "0.");
    }

    #[test]
    fn sign_flip_applies_to_the_entry_and_to_the_result() {
        let mut calculator = Calculator::new();
        press_all(&mut calculator, "5");
        calculator.press("\u{00b1}");
        assert_eq!(calculator.display(), "-5");
        calculator.press("=");
        calculator.press("\u{00b1}");
        assert_eq!(calculator.display(), "5");
    }

    #[test]
    fn percent_reads_as_a_share_of_the_running_value_next_to_a_sum() {
        assert_eq!(run("200+10%="), "220");
        assert_eq!(run("50%"), "0.5");
    }

    #[test]
    fn backspace_removes_one_character_of_the_entry() {
        assert_eq!(run("123<"), "12");
        assert_eq!(run("5<"), "0");
    }

    #[test]
    fn an_entry_stops_growing_once_a_double_cannot_tell_it_apart() {
        assert_eq!(run("12345678901234567890").len(), 15);
    }

    #[test]
    fn a_second_operator_replaces_the_first() {
        assert_eq!(run("3+*4="), "12");
    }
}

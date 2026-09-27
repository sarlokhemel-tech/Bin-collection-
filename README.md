# Hemel Daily Notebook

লাইটওয়েট, ফিচার-সমৃদ্ধ ব্যক্তিগত নোটবুক অ্যান্ড্রয়েড অ্যাপ।

## ফিচারসমূহ
- নতুন নোটবুক তৈরি (+ আইকন)
- শিরোনাম ও তারিখ (dd-MM-yyyy)
- নোটবুকের যেকোনো জায়গায় ছবি বসানো
- যেকোনো জায়গায় লেখার বক্স বসানো (ড্র্যাগ করে সরানো যায়)
- সারি-কলাম দিয়ে টেবিল/বক্স তৈরি করে তথ্য লেখা
- বক্স ডিলিট করা
- লেখার ফন্ট সাইজ ছোট-বড় করা
- পুরো নোটবুকের লেখা এক ক্লিকে কপি
- এক বা একাধিক নোটবুক PDF-এ এক্সপোর্ট
- সব নোটবুকের তালিকা + সার্চ (নাম, তারিখ বা ভেতরের লেখা দিয়ে)

## Termux দিয়ে GitHub-এ পুশ করা

```bash
cd HemelDailyNotebook
git init
git add .
git commit -m "Hemel Daily Notebook - initial version"
git branch -M main
git remote add origin https://github.com/<তোমার-ইউজারনেম>/<রিপোর নাম>.git
git push -u origin main
```

পুশ করার সাথে সাথেই `.github/workflows/build.yml` অনুযায়ী GitHub Actions স্বয়ংক্রিয়ভাবে APK তৈরি করবে। GitHub রিপোর **Actions** ট্যাবে গিয়ে সবশেষ workflow run খুলে **Artifacts** অংশ থেকে `hemel-daily-notebook-debug` ডাউনলোড করলেই APK পাওয়া যাবে (zip এর ভেতরে থাকবে)।

## এক কমান্ডে বিল্ড (GitHub CLI দিয়ে)

রিপো তৈরি হয়ে গেলে এবং লিংক দিলে, Termux-এ GitHub CLI (`gh`) ইনস্টল করা থাকলে নিচের একটাই কমান্ডে পুশ + বিল্ড ট্রিগার + রেজাল্ট মনিটর করা যাবে:

```bash
git add . && git commit -m "update" --allow-empty && git push && gh run watch $(gh run list -L 1 --json databaseId -q '.[0].databaseId')
```
